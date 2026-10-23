package com.auris.app.ui.music

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.remote.music.MusicCollectionItem
import com.auris.app.data.remote.music.MusicLibraryMapper
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.data.remote.music.MusicTrack
import com.auris.app.data.remote.music.StreamRefusalInfo
import com.auris.app.data.remote.music.youtubeThumbnailUrl
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.music.MusicPlaybackService
import com.auris.app.music.buildMediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A row in the browse list: a track, an album or a playlist. */
data class MusicRow(
    val kind: Kind,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    /** Video id for tracks; browse id for albums and playlists. */
    val id: String,
) {
    enum class Kind { TRACK, ALBUM, PLAYLIST }
}

/**
 * One titled row on the music home screen.
 *
 * Built by the ViewModel rather than the screen so the shelf's identity — which query filled it —
 * stays out of the UI, and a shelf that fails to load simply never appears.
 */
data class MusicHomeShelf(
    val title: String,
    val rows: List<MusicRow>,
)

/** Everything the browse screen renders. */
data class MusicBrowseUiState(
    val query: String = "",
    val results: List<MusicRow> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Parsed result counts, so an empty screen can be distinguished from a failed one. */
    val parsedTrackCount: Int = 0,
    val parsedAlbumCount: Int = 0,
    val parsedPlaylistCount: Int = 0,
    val isResolving: Boolean = false,
    val message: String? = null,
    val nowPlayingId: String? = null,
    /** Metadata for the now-playing strip, so it does not depend on the row still being listed. */
    val nowPlayingTitle: String? = null,
    val nowPlayingArtist: String? = null,
    val nowPlayingArtworkUrl: String? = null,
    /** Mirrors the service's play/pause state for the home strip. */
    val isPlaying: Boolean = false,
    /** Home shelves, filled progressively as each seed answers. */
    val shelves: List<MusicHomeShelf> = emptyList(),
    val isHomeLoading: Boolean = false,
    val homeError: String? = null,
) {
    val isEmpty: Boolean get() = results.isEmpty() && !isLoading && error == null

    /** True while the screen is showing the category tiles rather than a result list. */
    val isBrowsingCategories: Boolean get() = query.isBlank()
}

/**
 * Drives music browse and search, and playback through the [MusicPlaybackService].
 *
 * ### Why search is not debounced into oblivion
 * The query is debounced by [SEARCH_DEBOUNCE_MS] so typing does not fire a request per
 * keystroke — but it is not debounced so heavily that results feel stale. The catalogue API has
 * no rate limit we can rely on, so the debounce is the only rate control.
 *
 * ### Streams are resolved per playback, through the seam
 * Nothing here knows *which* provider serves audio. That is [MusicSource]'s business, which is
 * what let the audio half move from a first-party client to public Invidious instances without
 * this class changing beyond the interface it calls.
 */
class MusicBrowseViewModel(
    private val source: MusicSource,
    private val libraryRepository: LibraryRepository,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MusicBrowseUiState())
    val state: StateFlow<MusicBrowseUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var searchJob: kotlinx.coroutines.Job? = null

    /**
     * Completes when the service binding finishes, with the controller or null.
     *
     * A tap can arrive before the binding does — on a cold start the very first tap usually
     * does. Without this, that tap resolved a stream and then dropped the queue on the floor
     * with "Playback service is still starting", so the player opened on "Nothing is playing".
     */
    private val controllerReady = CompletableDeferred<MediaController?>()

    /** One-shot requests to surface the full player after audio actually started. */
    private val _playerOpenRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playerOpenRequests: SharedFlow<Unit> = _playerOpenRequests.asSharedFlow()

    /** Why the last playback attempt got no stream, so the failure can be reported honestly. */
    private var lastRefusal: StreamRefusalInfo? = null

    /** Why no provider answered at all, when that is the reason there is no stream. */
    private var lastError: String? = null

    /** Set once the home shelves have been requested, so recomposition cannot refetch them. */
    private var homeRequested = false

    init {
        connectController()
    }

    /**
     * Connects a [MediaController] to the playback service.
     *
     * A controller rather than direct player access, because the player lives in the service and
     * the screen may be destroyed while playback continues.
     */
    private fun connectController() {
        val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        // `MoreExecutors.directExecutor()` rather than the main executor: the listener body
        // only assigns a field, and running it inline avoids a dispatch hop while the service
        // is still binding.
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()
                controller?.addListener(
                    object : androidx.media3.common.Player.Listener {
                        override fun onEvents(
                            player: androidx.media3.common.Player,
                            events: androidx.media3.common.Player.Events,
                        ) {
                            _state.value = _state.value.copy(isPlaying = player.isPlaying)
                        }
                    },
                )
                controllerReady.complete(controller)
            },
            MoreExecutors.directExecutor(),
        )
    }

    // ---- Search -------------------------------------------------------------

    /** Runs a search, replacing any in-flight one. */
    fun search(query: String) {
        searchJob?.cancel()

        // A blank query is the category-tile landing, not a request. Nothing is fetched and the
        // old results are dropped so the tiles cannot sit under a stale list.
        if (query.isBlank()) {
            _state.value = _state.value.copy(
                query = query,
                results = emptyList(),
                isLoading = false,
                error = null,
                parsedTrackCount = 0,
                parsedAlbumCount = 0,
                parsedPlaylistCount = 0,
            )
            return
        }

        _state.value = _state.value.copy(query = query, isLoading = true, error = null)

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            source.search(query, BROWSE_LIMIT)
                .onSuccess { items -> _state.value = _state.value.withResults(items, isLoading = false) }
                .onFailure { error -> _state.value = _state.value.failed(error) }
        }
    }

    // ---- Home ---------------------------------------------------------------

    /**
     * Loads the home shelves, once.
     *
     * The seeds run sequentially rather than concurrently. Firing five InnerTube requests at
     * once is the fastest way to be throttled, and there is no documented limit to reason
     * about — each shelf appears as it arrives instead.
     *
     * A seed that fails is skipped rather than failing the page: one bad response should not
     * leave the user with an empty home.
     */
    fun loadHome() {
        if (homeRequested) return
        homeRequested = true

        viewModelScope.launch {
            _state.value = _state.value.copy(isHomeLoading = true, homeError = null)

            val loaded = mutableListOf<MusicHomeShelf>()

            for (seed in source.discoveryShelves) {
                val items = source.search(seed.seed, SHELF_LIMIT).getOrNull().orEmpty()
                val rows = items.flatMap { item -> item.toRows() }.take(SHELF_ROW_LIMIT)
                if (rows.isEmpty()) continue

                loaded += MusicHomeShelf(seed.title, rows)
                // Published after each shelf so the screen fills in progressively rather than
                // waiting for the slowest query.
                _state.value = _state.value.copy(shelves = loaded.toList())
            }

            _state.value = _state.value.copy(
                isHomeLoading = false,
                homeError = if (loaded.isEmpty()) "Could not reach YouTube Music" else null,
            )
        }
    }

    private fun MusicBrowseUiState.withResults(
        items: List<MusicCollectionItem>,
        isLoading: Boolean,
    ): MusicBrowseUiState {
        val rows = items.flatMap { item -> item.toRows() }
        return copy(
            results = rows,
            isLoading = isLoading,
            parsedTrackCount = items.sumOf { it.tracks.size },
            parsedAlbumCount = items.count { it.album != null },
            parsedPlaylistCount = items.count { it.playlist != null },
        )
    }

    private fun MusicBrowseUiState.failed(error: Throwable): MusicBrowseUiState = copy(
        isLoading = false,
        // A failure here is usually the client version drifting, not a transient network issue,
        // so the message says so rather than suggesting a retry will help.
        error = error.message?.takeIf { it.isNotBlank() }
            ?: "Could not reach YouTube Music",
    )

    // ---- Playback -----------------------------------------------------------

    /**
     * Plays a row.
     *
     * Tracks resolve a stream and enqueue it; albums and playlists browse their track list and
     * enqueue the whole thing, which is what makes the notification's Next button meaningful.
     *
     * @param openPlayer asks the caller to open the full player once audio has actually
     *   started. Home and Library used to navigate immediately and unconditionally, which is
     *   why a tap showed whatever was already playing — or "Nothing is playing" on a cold start.
     */
    fun play(row: MusicRow, openPlayer: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isResolving = true, error = null, message = null)
            lastRefusal = null
            lastError = null

            val tracks = when (row.kind) {
                MusicRow.Kind.TRACK -> {
                    // Start the cover lookup now, alongside the stream resolve, rather than after
                    // it. On a slow network a stream takes several seconds; the artwork request
                    // is then already finished and costs nothing. The artwork is attached after
                    // the resolve so the stream starts immediately — awaiting it as an argument
                    // would put the two requests back in sequence.
                    val artwork = async { officialArtwork(row.title, row.artistHint()) }
                    val playable = resolveTrack(row.id, row.title)
                    listOfNotNull(playable?.withArtwork(artwork.await()))
                }
                else -> collectionTracks(row.id).mapNotNull { resolveTrack(it) }
            }

            if (tracks.isEmpty()) {
                _state.value = _state.value.copy(
                    isResolving = false,
                    message = StreamRefusal.describe(row.title, lastRefusal, lastError),
                )
                return@launch
            }

            if (!enqueue(tracks)) {
                _state.value = _state.value.copy(isResolving = false)
                return@launch
            }

            val first = tracks.first().track
            _state.value = _state.value.copy(
                isResolving = false,
                nowPlayingId = first.videoId,
                nowPlayingTitle = first.title,
                nowPlayingArtist = first.artist,
                nowPlayingArtworkUrl = first.artworkUrl,
                message = "Playing ${first.title}",
            )

            if (openPlayer) _playerOpenRequests.tryEmit(Unit)
        }
    }

    /** Pauses or resumes the current queue, for the home screen's now-playing strip. */
    fun togglePlayPause() {
        val player = controller ?: return
        when {
            player.isPlaying -> player.pause()
            player.playbackState == androidx.media3.common.Player.STATE_IDLE -> {
                player.prepare()
                player.play()
            }
            else -> player.play()
        }
    }

    /**
     * Resolves a catalogue track once, keeping the stream it returned.
     *
     * The stream used to be resolved twice per tap — once for metadata and again for the queue —
     * which doubled the latency and gave the second resolve a chance to fail on its own.
     */
    private suspend fun resolveTrack(
        videoId: String,
        fallbackTitle: String,
    ): PlayableTrack? {
        val resolved = source.resolveAudioStream(videoId).getOrElse { error ->
            // No provider answered at all — every instance was unreachable, or none is
            // configured. Recorded so [play] can point at Settings rather than implying the
            // track is gone.
            lastError = error.message ?: error::class.java.simpleName
            return null
        }

        // Recorded before the early return so [play] can say *why* there is no stream rather
        // than reporting a generic failure. See [StreamRefusal].
        lastRefusal = resolved.refusal

        val stream = resolved.stream?.takeIf { it.isPlayable() } ?: return null

        val title = resolved.title ?: fallbackTitle

        // Artwork is derived from the video id, not taken from the resolve. The resolver does
        // return thumbnail URLs, but they are instance-relative and the instance answers them
        // with an HTML error page rather than a JPEG (see [youtubeThumbnailUrl]) — so using them
        // painted broken images on every played track.
        return PlayableTrack(
            track = MusicTrack(
                videoId = videoId,
                title = title,
                artist = resolved.author,
                album = null,
                durationMs = stream.durationMs ?: resolved.lengthMs,
                artworkUrl = youtubeThumbnailUrl(videoId),
                largeArtworkUrl = youtubeThumbnailUrl(videoId, large = true),
                playlistId = null,
                trackIndex = null,
            ),
            streamUrl = stream.url,
            mimeType = stream.mimeType,
        )
    }

    /**
     * The artist part of a row's subtitle, for the cover lookup.
     *
     * Rows are `"Artist • Album • Year"`; the resolver's own metadata is not available yet when
     * the lookup is launched, which is the point — it runs in parallel with the stream resolve.
     * A wrong hint only costs a miss, and a miss falls back to the thumbnail.
     */
    private fun MusicRow.artistHint(): String? =
        subtitle?.substringBefore("•")?.trim()?.takeIf { it.isNotEmpty() }

    /** Best-effort official cover, bounded so a slow metadata server cannot stall playback. */
    private suspend fun officialArtwork(title: String, artist: String?): String? =
        withTimeoutOrNull(ARTWORK_TIMEOUT_MS) { source.findArtwork(title, artist) }

    /** Replaces the thumbnail with official art when the lookup found any. */
    private fun PlayableTrack.withArtwork(artworkUrl: String?): PlayableTrack =
        if (artworkUrl == null) {
            this
        } else {
            copy(
                track = track.copy(
                    artworkUrl = artworkUrl,
                    largeArtworkUrl = artworkUrl,
                ),
            )
        }

    /** Resolves a track that already carries its metadata (album and playlist entries). */
    private suspend fun resolveTrack(track: MusicTrack): PlayableTrack? {
        val resolved = source.resolveAudioStream(track.videoId).getOrElse { error ->
            lastError = lastError ?: error.message ?: error::class.java.simpleName
            return null
        }

        resolved.refusal?.let { lastRefusal = lastRefusal ?: it }

        val stream = resolved.stream?.takeIf { it.isPlayable() } ?: return null
        return PlayableTrack(
            // Collection tracks normally carry the album cover already. The thumbnail is only a
            // fallback for rows that came back without artwork.
            track = track.copy(
                artworkUrl = track.artworkUrl ?: youtubeThumbnailUrl(track.videoId),
                largeArtworkUrl = track.largeArtworkUrl ?: track.artworkUrl
                    ?: youtubeThumbnailUrl(track.videoId, large = true),
            ),
            streamUrl = stream.url,
            mimeType = stream.mimeType,
        )
    }

    private suspend fun collectionTracks(browseId: String): List<MusicTrack> =
        source.collection(browseId, COLLECTION_TRACK_LIMIT)
            .getOrNull()
            .orEmpty()
            .flatMap { it.tracks }

    /**
     * Hands the resolved queue to the playback service.
     *
     * Each track is resolved individually rather than trusting one URL for the queue: InnerTube
     * URLs are per-video, so a single resolved URL cannot cover an album.
     *
     * Tracks are resolved sequentially with a small gap. InnerTube has no documented rate limit
     * and firing twenty player requests at once is a good way to get throttled.
     *
     * @return true when the service accepted the queue.
     */
    private suspend fun enqueue(tracks: List<PlayableTrack>): Boolean {
        // Wait for the binding rather than failing the tap: on a cold start the service is still
        // connecting when the first card is tapped.
        val sessionController = controller ?: controllerReady.await()

        if (sessionController == null) {
            _state.value = _state.value.copy(message = "Could not reach the playback service")
            return false
        }

        val items = tracks.map { playable ->
            buildMediaItem(
                videoId = playable.track.videoId,
                title = playable.track.title,
                artist = playable.track.artist,
                album = playable.track.album,
                artworkUrl = playable.track.largeArtworkUrl ?: playable.track.artworkUrl,
                streamUrl = playable.streamUrl,
                mimeType = playable.mimeType,
            )
        }

        sessionController.setMediaItems(items, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        sessionController.prepare()
        sessionController.play()

        // Persist the first track so the library grid shows it and progress is restorable.
        runCatching {
            libraryRepository.upsert(
                MusicLibraryMapper.toEntry(tracks.first().track, isCurrent = true),
            )
        }
        return true
    }

    /** Saves a track to the library without playing it. */
    fun saveToLibrary(row: MusicRow) {
        if (row.kind != MusicRow.Kind.TRACK) return
        viewModelScope.launch {
            val track = MusicTrack(
                videoId = row.id,
                title = row.title,
                artist = row.subtitle?.substringBefore("•")?.trim(),
                album = row.subtitle?.substringAfter("•", "")?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = null,
                artworkUrl = row.artworkUrl,
                largeArtworkUrl = row.artworkUrl,
                playlistId = null,
                trackIndex = null,
            )
            runCatching { libraryRepository.upsert(MusicLibraryMapper.toEntry(track)) }
                .onSuccess { _state.value = _state.value.copy(message = "Saved ${track.title}") }
                .onFailure { _state.value = _state.value.copy(message = "Could not save") }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        // Released, not disconnected-and-forgotten: a leaked controller keeps a binder
        // registration to a service that outlives the screen.
        controller?.release()
        controller = null
        super.onCleared()
    }

    private class Factory(
        private val source: MusicSource,
        private val libraryRepository: LibraryRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MusicBrowseViewModel(source, libraryRepository, context) as T
    }

    companion object {
        /**
         * Debounce before searching.
         *
         * Long enough to avoid a request per keystroke, short enough that results feel
         * responsive. The catalogue API has no documented rate limit, so this is the only rate
         * control the client has.
         */
        const val SEARCH_DEBOUNCE_MS = 350L

        private const val BROWSE_LIMIT = 40

        /** Per shelf, so five shelves do not each load a full 40-row page. */
        private const val SHELF_LIMIT = 20

        /** Cards shown per shelf. Enough to scroll, few enough that five rows stay cheap. */
        private const val SHELF_ROW_LIMIT = 15

        /** Guard on a large playlist so one album cannot page forever. */
        private const val COLLECTION_TRACK_LIMIT = 200

        /**
         * Caps the cover-art lookup.
         *
         * Runs in parallel with the stream resolve, so this is not normally added to playback
         * latency. Four seconds is deliberately generous for a throttled mobile connection; the
         * request itself is already bounded by the artwork client's own call timeout.
         */
        private const val ARTWORK_TIMEOUT_MS = 4_000L

        fun factory(
            source: MusicSource,
            libraryRepository: LibraryRepository,
            context: Context,
        ): ViewModelProvider.Factory = Factory(source, libraryRepository, context)
    }
}

/**
 * A track plus the stream that will be enqueued for it.
 *
 * Keeping them together is what removes the second resolve: the stream returned by the tap's
 * resolve is the one the queue uses.
 */
private data class PlayableTrack(
    val track: MusicTrack,
    val streamUrl: String,
    val mimeType: String,
)

/**
 * Flattens a catalogue answer into rows the UI can render.
 *
 * Top-level rather than a member so the home shelves and the search results share one mapping:
 * an album that renders one way in search cannot render another way on a shelf.
 */
internal fun MusicCollectionItem.toRows(): List<MusicRow> = buildList {
    tracks.forEach { track ->
        add(
            MusicRow(
                kind = MusicRow.Kind.TRACK,
                title = track.title,
                subtitle = listOfNotNull(track.artist, track.album).joinToString(" • ")
                    .takeIf { it.isNotBlank() },
                artworkUrl = track.artworkUrl,
                id = track.videoId,
            ),
        )
    }
    album?.let {
        add(
            MusicRow(
                kind = MusicRow.Kind.ALBUM,
                title = it.title,
                subtitle = it.artist,
                artworkUrl = it.artworkUrl,
                id = it.browseId,
            ),
        )
    }
    playlist?.let {
        add(
            MusicRow(
                kind = MusicRow.Kind.PLAYLIST,
                title = it.title,
                subtitle = it.subtitle,
                artworkUrl = it.artworkUrl,
                id = it.browseId,
            ),
        )
    }
}