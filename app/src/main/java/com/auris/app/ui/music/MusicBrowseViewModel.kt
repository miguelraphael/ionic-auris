package com.auris.app.ui.music

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.remote.music.MusicCollectionItem
import com.auris.app.data.remote.music.MusicLibraryMapper
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.data.remote.music.MusicResultType
import com.auris.app.data.remote.music.MusicTrack
import com.auris.app.data.remote.music.MusicArtistDetails
import com.auris.app.data.remote.music.MusicSongContext
import com.auris.app.data.remote.music.StreamRefusalInfo
import com.auris.app.data.remote.music.cleanArtist
import com.auris.app.data.remote.music.youtubeThumbnailUrl
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.music.MusicPlaybackService
import com.auris.app.music.buildMediaItem
import com.auris.app.music.resumeIfEnded
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import com.auris.app.music.MusicSuggestions
import kotlinx.coroutines.flow.first
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
    val resultType: MusicResultType = MusicResultType.UNKNOWN,
    val albumArtworkUrl: String? = null,
) {
    enum class Kind { TRACK, ALBUM, PLAYLIST, ARTIST }
}

/** Extracts the clean artist name from a row's subtitle or metadata. */
internal fun MusicRow.artistHint(): String? =
    if (kind == MusicRow.Kind.ARTIST) title.cleanArtist().takeIf { it.isNotEmpty() }
    else subtitle?.substringBefore("•")?.cleanArtist()?.takeIf { it.isNotEmpty() }

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
    val isBuffering: Boolean = false,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
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
    private var playbackJob: Job? = null
    private var playbackRequest = 0
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
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()
                controller?.addListener(
                    object : androidx.media3.common.Player.Listener {
                        override fun onEvents(
                            player: androidx.media3.common.Player,
                            events: androidx.media3.common.Player.Events,
                        ) {
                            if (!_state.value.isResolving) syncPlayback(player)
                        }
                    },
                )
                if (!_state.value.isResolving) controller?.let { syncPlayback(it) }
                controllerReady.complete(controller)
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun syncPlayback(player: androidx.media3.common.Player) {
        val metadata = player.mediaMetadata
        _state.value = _state.value.copy(
            nowPlayingId = player.currentMediaItem?.mediaId,
            nowPlayingTitle = metadata.title?.toString(),
            nowPlayingArtist = metadata.artist?.toString(),
            nowPlayingArtworkUrl = metadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == androidx.media3.common.Player.STATE_BUFFERING,
            canGoPrevious = player.currentMediaItem != null,
            canGoNext = player.hasNextMediaItem(),
        )
    }

    // ---- Search -------------------------------------------------------------

    /** Runs a search, replacing any in-flight one. */
    fun search(query: String) {
        searchJob?.cancel()

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
            source.searchWithAlbums(query, BROWSE_LIMIT)
                .onSuccess { items ->
                    currentCoroutineContext().ensureActive()
                    _state.value = _state.value.withResults(items, isLoading = false)
                }
                .onFailure { error ->
                    currentCoroutineContext().ensureActive()
                    _state.value = _state.value.failed(error)
                }
        }
    }

    // ---- Home ---------------------------------------------------------------

    /**
     * Loads the home shelves, once.
     */
    fun loadHome() {
        if (homeRequested) return
        homeRequested = true

        viewModelScope.launch {
            _state.value = _state.value.copy(isHomeLoading = true, homeError = null)

            val loaded = mutableListOf<MusicHomeShelf>()
            val suggestions = MusicSuggestions.suggest(source, libraryRepository.observeLibrary().first())
            if (suggestions.isNotEmpty()) {
                val rows = MusicCollectionItem(kind = MusicCollectionItem.Kind.TRACK, tracks = suggestions).toRows()
                loaded += MusicHomeShelf("Recommended for you", rows)
                _state.value = _state.value.copy(shelves = loaded.toList())
            }

            for (seed in source.discoveryShelves) {
                val items = source.search(seed.seed, SHELF_LIMIT).getOrNull().orEmpty()
                val rows = items.flatMap { item -> item.toRows() }.take(SHELF_ROW_LIMIT)
                if (rows.isEmpty()) continue

                loaded += MusicHomeShelf(seed.title, rows)
                _state.value = _state.value.copy(shelves = loaded.toList())
            }

            _state.value = _state.value.copy(
                isHomeLoading = false,
                homeError = if (loaded.isEmpty()) "Could not reach YouTube Music" else null,
            )
        }
    }

    /** Retries the home shelves after a failed load. */
    fun retryHome() {
        homeRequested = false
        loadHome()
    }

    private fun MusicBrowseUiState.withResults(
        items: List<MusicCollectionItem>,
        isLoading: Boolean,
    ): MusicBrowseUiState {
        val rows = items.flatMap { item -> item.toRows() }
            .filter { it.id.isNotBlank() }.sortedBy { it.resultType.searchPriority }
            .distinctBy { it.kind to it.id }
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
        error = error.message?.takeIf { it.isNotBlank() }
            ?: "Could not reach YouTube Music",
    )

    // ---- Playback -----------------------------------------------------------

    /**
     * Plays a row.
     *
     * Tracks resolve a stream and enqueue it; albums and playlists browse their track list and
     * enqueue the whole thing, which is what makes the notification's Next button meaningful.
     */
    fun play(row: MusicRow, openPlayer: Boolean = false) {
        val request = ++playbackRequest
        playbackJob?.cancel()
        _state.value = _state.value.copy(
            isResolving = true, error = null, message = null,
            nowPlayingId = row.id, nowPlayingTitle = row.title,
            nowPlayingArtist = row.artistHint(), nowPlayingArtworkUrl = row.artworkUrl,
        )
        playbackJob = viewModelScope.launch {
            try {
                lastRefusal = null
                lastError = null

                val tracks = when (row.kind) {
                    MusicRow.Kind.TRACK -> {
                        val artwork = async { officialArtwork(row.title, row.artistHint()) }
                        val playable = resolveTrack(row.id, fallbackTitle = row.title, artistHint = row.artistHint())
                        listOfNotNull(playable?.withArtwork(artwork.await() ?: row.albumArtworkUrl ?: row.artworkUrl))
                    }
                    else -> collectionTracks(row.id).mapNotNull { resolveTrack(it) }
                }

                currentCoroutineContext().ensureActive()
                if (tracks.isEmpty()) {
                    _state.value = _state.value.copy(
                        isResolving = false,
                        message = StreamRefusal.describe(row.title, lastRefusal, lastError),
                    )
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                if (!enqueue(tracks)) {
                    _state.value = _state.value.copy(isResolving = false)
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                val first = tracks.first().track
                _state.value = _state.value.copy(
                    isResolving = false,
                    nowPlayingId = first.videoId,
                    nowPlayingTitle = first.title,
                    nowPlayingArtist = first.artist,
                    nowPlayingArtworkUrl = first.artworkUrl,
                    message = null,
                )

                controller?.let { syncPlayback(it) }
                if (openPlayer) _playerOpenRequests.tryEmit(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = _state.value.copy(message = error.message ?: "Could not play this track")
            } finally {
                if (request == playbackRequest) {
                    _state.value = _state.value.copy(isResolving = false)
                    controller?.let { syncPlayback(it) }
                }
            }
        }
    }

    fun pause() {
        controller?.pause()
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

    fun previous() {
        val player = controller ?: return
        if (_state.value.isResolving) return
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0L)
    }

    fun next() {
        val player = controller ?: return
        if (!_state.value.isResolving && player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /**
     * Resolves a catalogue track once, keeping the stream it returned.
     * Preserves clean metadata (title, artist) if known from catalogue/library, rather than
     * overwriting it with YouTube's raw video upload title (e.g. "(Official Lyric Video)").
     */
    private suspend fun resolveTrack(
        videoId: String,
        fallbackTitle: String? = null,
        artistHint: String? = null,
    ): PlayableTrack? {
        val resolved = source.resolveAudioStream(videoId).getOrElse { error ->
            lastError = error.message ?: error::class.java.simpleName
            return null
        }

        lastRefusal = resolved.refusal

        val stream = resolved.stream?.takeIf { it.isPlayable() } ?: return null

        val title = fallbackTitle?.takeIf { it.isNotBlank() } ?: resolved.title ?: videoId
        val artist = artistHint?.takeIf { it.isNotBlank() } ?: resolved.author?.cleanArtist()

        return PlayableTrack(
            track = MusicTrack(
                videoId = videoId,
                title = title,
                artist = artist,
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
            track = track.copy(
                artist = track.artist?.takeIf { it.isNotBlank() }?.cleanArtist() ?: resolved.author?.cleanArtist(),
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
     */
    private suspend fun enqueue(tracks: List<PlayableTrack>, startIndex: Int = 0): Boolean {
        val sessionController = controller ?: controllerReady.await()

        if (sessionController == null) {
            _state.value = _state.value.copy(message = "Could not reach the playback service")
            return false
        }

        val items = tracks.map { playable ->
            buildMediaItem(
                videoId = playable.track.videoId,
                title = playable.track.title,
                artist = playable.track.artist?.cleanArtist(),
                album = playable.track.album,
                artworkUrl = playable.track.largeArtworkUrl ?: playable.track.artworkUrl,
                streamUrl = playable.streamUrl,
                mimeType = playable.mimeType,
            )
        }

        sessionController.setMediaItems(items, startIndex, /* startPositionMs = */ 0L)
        sessionController.prepare()
        sessionController.play()
        homeRequested = false

        // Persist the first track so the library grid shows it and progress is restorable.
        upsertRecentTrack(libraryRepository, tracks[startIndex].track)
        return true
    }

    suspend fun isFavorite(row: MusicRow): Boolean = libraryRepository.findBySource(
        com.auris.app.core.model.MediaType.MUSIC, row.id, MusicLibraryMapper.SOURCE_KEY)?.isFavorite == true

    suspend fun favorite(row: MusicRow, enabled: Boolean) {
        val existing = libraryRepository.findBySource(com.auris.app.core.model.MediaType.MUSIC,
            row.id, MusicLibraryMapper.SOURCE_KEY)
        val entry = existing ?: MusicLibraryMapper.toEntry(MusicTrack(videoId = row.id,
            title = row.title, artist = row.artistHint(), artworkUrl = row.artworkUrl,
            album = null, durationMs = null, largeArtworkUrl = null, playlistId = null, trackIndex = null))
        libraryRepository.upsert(entry.copy(isFavorite = enabled))
    }

    suspend fun addToQueue(row: MusicRow, next: Boolean) {
        val track = resolveTrack(row.id, fallbackTitle = row.title, artistHint = row.artistHint())
            ?: error("Could not resolve this song. Try again.")
        val session = controller ?: controllerReady.await() ?: error("Playback service unavailable")
        val item = buildMediaItem(row.id, row.title, row.artistHint(), track.track.album,
            row.artworkUrl, track.streamUrl, track.mimeType)
        val empty = session.mediaItemCount == 0
        val index = if (next && !empty) session.currentMediaItemIndex + 1 else session.mediaItemCount
        session.addMediaItem(index, item)
        if (empty) { session.prepare(); session.play() }
    }

    suspend fun radio(row: MusicRow) {
        val cleanArtist = row.artistHint().orEmpty()
        val seed = resolveTrack(row.id, fallbackTitle = row.title, artistHint = row.artistHint())
            ?: error("Could not start radio for this song.")
        enqueue(listOf(seed))

        val history = libraryRepository.observeLibrary().first()
        val suggestions = MusicSuggestions.suggest(
            source = source,
            history = history,
            seedArtist = cleanArtist.takeIf { it.isNotBlank() },
            excludedIds = setOf(row.id),
            limit = 10,
        )

        val session = controller ?: controllerReady.await() ?: return
        for (track in suggestions) {
            val resolved = resolveTrack(track) ?: continue
            val item = buildMediaItem(
                videoId = resolved.track.videoId,
                title = resolved.track.title,
                artist = resolved.track.artist?.cleanArtist(),
                album = resolved.track.album,
                artworkUrl = resolved.track.largeArtworkUrl ?: resolved.track.artworkUrl,
                streamUrl = resolved.streamUrl,
                mimeType = resolved.mimeType,
            )
            session.addMediaItem(item)
        }
    }

    suspend fun explore(row: MusicRow, album: Boolean): List<MusicRow> {
        val artist = row.artistHint().orEmpty().ifBlank { row.title }
        val query = if (album) "${row.title} $artist".trim() else artist
        if (query.isBlank()) return emptyList()
        val results = source.search(query, 40).getOrNull().orEmpty()
        val rows = results.flatMap { it.toRows() }
        return if (album) {
            rows.filter { it.kind == MusicRow.Kind.ALBUM }
        } else {
            rows.filter { item ->
                val itemArtist = item.artistHint().orEmpty()
                item.kind == MusicRow.Kind.TRACK ||
                    itemArtist.contains(artist, ignoreCase = true) ||
                    artist.contains(itemArtist, ignoreCase = true) ||
                    item.title.contains(artist, ignoreCase = true)
            }.sortedBy { it.resultType.searchPriority }.distinctBy { it.kind to it.id }
        }
    }

    suspend fun goToAlbum(row: MusicRow): Pair<String?, List<MusicRow>> {
        val ctx = source.songContext(row.id).getOrNull()
        val albumId = ctx?.albumBrowseId
        val albumTitle = ctx?.albumTitle ?: row.subtitle?.substringAfter("•", "")?.trim()?.takeIf { it.isNotEmpty() }
        if (!albumId.isNullOrBlank()) {
            val albumRows = source.collection(albumId, COLLECTION_TRACK_LIMIT).getOrNull().orEmpty()
                .flatMap { it.toRows() }
                .filter { it.kind == MusicRow.Kind.TRACK }
            if (albumRows.isNotEmpty()) {
                return Pair(albumTitle ?: row.title, albumRows)
            }
        }
        val artist = row.artistHint().orEmpty()
        val query = if (!albumTitle.isNullOrBlank()) "$albumTitle $artist".trim() else "${row.title} $artist".trim()
        val searchResults = source.search(query, 30).getOrNull().orEmpty().flatMap { it.toRows() }
        val albums = searchResults.filter { it.kind == MusicRow.Kind.ALBUM }
        return Pair(null, albums)
    }

    suspend fun loadArtist(row: MusicRow): Pair<MusicArtistDetails?, List<MusicRow>> {
        val cleanName = if (row.kind == MusicRow.Kind.ARTIST) row.title.cleanArtist() else row.artistHint().orEmpty().ifBlank { row.title }
        val ctx = if (row.id.isNotBlank() && !row.id.startsWith("artist-") && row.kind != MusicRow.Kind.ARTIST) {
            source.songContext(row.id).getOrNull()
        } else null
        val artistId = if (row.kind == MusicRow.Kind.ARTIST && row.id.isNotBlank() && !row.id.startsWith("artist-")) {
            row.id
        } else {
            ctx?.artistBrowseId
        }
        if (!artistId.isNullOrBlank()) {
            val details = source.artistDetails(artistId, cleanName).getOrNull()
            val collection = source.collection(artistId, COLLECTION_TRACK_LIMIT).getOrNull().orEmpty()
            val rows = collection.flatMap { it.toRows() }
            if (rows.isNotEmpty() || details != null) {
                return Pair(details ?: MusicArtistDetails(artistId, cleanName), rows)
            }
        }
        val searchRows = explore(row.copy(title = cleanName, subtitle = cleanName), false)
        val details = MusicArtistDetails(
            id = artistId ?: "",
            name = cleanName,
            bio = null,
            photoUrl = searchRows.firstOrNull { it.artworkUrl != null }?.artworkUrl ?: row.artworkUrl,
        )
        return Pair(details, searchRows)
    }

    suspend fun collectionRows(id: String): List<MusicRow> = source.collection(id, COLLECTION_TRACK_LIMIT)
        .getOrThrow().flatMap { it.toRows() }

    fun openPlayer() { _playerOpenRequests.tryEmit(Unit) }

    suspend fun playPlaylist(rows: List<MusicRow>, startIndex: Int = 0) {
        val selected = rows.getOrNull(startIndex) ?: error("No song selected")
        val request = ++playbackRequest
        playbackJob?.cancel()
        val started = CompletableDeferred<Unit>()
        _state.value = _state.value.copy(isResolving = true, message = null,
            nowPlayingId = selected.id, nowPlayingTitle = selected.title,
            nowPlayingArtist = selected.artistHint(), nowPlayingArtworkUrl = selected.artworkUrl)
        playbackJob = viewModelScope.launch {
            try {
                startProgressivePlaylist(rows, startIndex,
                    resolve = { resolveTrack(it.id, fallbackTitle = it.title, artistHint = it.artistHint())?.withArtwork(it.albumArtworkUrl ?: it.artworkUrl) },
                    start = { first ->
                        check(enqueue(listOf(first))) { "Could not reach the playback service" }
                        _state.value = _state.value.copy(isResolving = false)
                        started.complete(Unit)
                    },
                    insert = { index, playable ->
                        val item = buildMediaItem(playable.track.videoId, playable.track.title,
                            playable.track.artist, playable.track.album,
                            playable.track.largeArtworkUrl ?: playable.track.artworkUrl,
                            playable.streamUrl, playable.mimeType)
                        controller?.let {
                            it.addMediaItem(index.coerceAtMost(it.mediaItemCount), item)
                            resumeIfEnded(it)
                        }
                        // A queued track is history-worthy the moment it is inserted; only
                        // the first track previously reached the library.
                        viewModelScope.launch { upsertRecentTrack(libraryRepository, playable.track) }
                    })
            } catch (error: CancellationException) {
                started.cancel(error)
                throw error
            } catch (error: Exception) {
                if (!started.isCompleted) started.completeExceptionally(error)
                else _state.value = _state.value.copy(message = "Some songs could not be added. Playback can continue.")
            } finally {
                if (playbackRequest == request) _state.value = _state.value.copy(isResolving = false)
            }
        }
        started.await()
    }

    /** Saves a track to the library without playing it. */
    fun saveToLibrary(row: MusicRow) {
        if (row.kind != MusicRow.Kind.TRACK) return
        viewModelScope.launch {
            val track = MusicTrack(
                videoId = row.id,
                title = row.title,
                artist = row.artistHint(),
                album = row.subtitle?.substringAfter("•", "")?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = null,
                artworkUrl = row.artworkUrl,
                largeArtworkUrl = row.artworkUrl,
                playlistId = null,
                trackIndex = null,
            )
            runCatching {
                val existing = libraryRepository.findBySource(com.auris.app.core.model.MediaType.MUSIC,
                    track.videoId, MusicLibraryMapper.SOURCE_KEY)
                if (existing == null) libraryRepository.upsert(MusicLibraryMapper.toEntry(track))
            }
                .onSuccess { _state.value = _state.value.copy(message = "Saved ${track.title}") }
                .onFailure { _state.value = _state.value.copy(message = "Could not save") }
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val ARTWORK_TIMEOUT_MS = 1_500L
        private const val BROWSE_LIMIT = 25
        private const val SHELF_LIMIT = 6
        private const val SHELF_ROW_LIMIT = 10
        private const val COLLECTION_TRACK_LIMIT = 50

        fun factory(
            source: MusicSource,
            libraryRepository: LibraryRepository,
            context: Context,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MusicBrowseViewModel(source, libraryRepository, context.applicationContext) as T
        }
    }
}

private data class PlayableTrack(
    val track: MusicTrack,
    val streamUrl: String,
    val mimeType: String,
)

/**
 * Persists one played or queued track into Recently Played, preserving a favorite
 * already set on it. Best-effort, like the original inline upsert it replaces:
 * a library failure must never interrupt playback.
 */
internal suspend fun upsertRecentTrack(libraryRepository: LibraryRepository, track: MusicTrack) {
    runCatching {
        val existing = libraryRepository.findBySource(
            com.auris.app.core.model.MediaType.MUSIC, track.videoId, MusicLibraryMapper.SOURCE_KEY)
        libraryRepository.upsert(
            MusicLibraryMapper.toEntry(track, isCurrent = true)
                .copy(isFavorite = existing?.isFavorite ?: false),
        )
    }
}

/**
 * Maps a collection item into display rows.
 *
 * Top-level rather than a member so the home shelves and the search results share one mapping:
 * an album that renders one way in search cannot render another way on a shelf.
 */
internal fun MusicCollectionItem.toRows(): List<MusicRow> = buildList {
    tracks.forEach { track ->
        val artist = track.artist?.cleanArtist()
        add(
            MusicRow(
                kind = MusicRow.Kind.TRACK,
                title = track.title,
                // The first subtitle segment is used as the artist at playback time. An
                // album-only subtitle would incorrectly turn its title into an artist hint.
                subtitle = artist?.let { listOfNotNull(it, track.album).joinToString(" • ") },
                artworkUrl = track.largeArtworkUrl ?: track.artworkUrl,
                albumArtworkUrl = track.albumArtworkUrl,
                id = track.videoId,
                resultType = track.resultType,
            ),
        )
    }
    album?.let {
        add(
            MusicRow(
                kind = MusicRow.Kind.ALBUM,
                title = it.title,
                subtitle = it.artist?.cleanArtist(),
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
                subtitle = it.subtitle?.cleanArtist(),
                artworkUrl = it.artworkUrl,
                id = it.browseId,
            ),
        )
    }
    artist?.let {
        add(
            MusicRow(
                kind = MusicRow.Kind.ARTIST,
                title = it.name,
                subtitle = "Artist",
                artworkUrl = it.photoUrl,
                id = it.id,
            ),
        )
    }
}
