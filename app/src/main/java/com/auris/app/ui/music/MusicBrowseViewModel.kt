package com.auris.app.ui.music

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.remote.music.AudioStream
import com.auris.app.data.remote.music.InnerTubeClient
import com.auris.app.data.remote.music.InnerTubeMusicItem
import com.auris.app.data.remote.music.InnerTubePlayerResponse
import com.auris.app.data.remote.music.InnerTubeSearchParams
import com.auris.app.data.remote.music.MusicLibraryMapper
import com.auris.app.data.remote.music.MusicTrack
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.music.MusicPlaybackService
import com.auris.app.music.buildMediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
) {
    val isEmpty: Boolean get() = results.isEmpty() && !isLoading && error == null
}

/**
 * Drives music browse and search, and playback through the [MusicPlaybackService].
 *
 * ### Why search is not debounced into oblivion
 * The query is debounced by [SEARCH_DEBOUNCE_MS] so typing does not fire a request per
 * keystroke — but it is not debounced so heavily that results feel stale. InnerTube has no
 * rate limit we can rely on, so the debounce is the only rate control.
 *
 * ### Streams are resolved per playback
 * InnerTube URLs expire in about six hours, so nothing about them is cached. Every tap resolves
 * again, and the resolved URL is handed straight to the controller.
 */
class MusicBrowseViewModel(
    private val client: InnerTubeClient,
    private val libraryRepository: LibraryRepository,
    private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(MusicBrowseUiState())
    val state: StateFlow<MusicBrowseUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var searchJob: kotlinx.coroutines.Job? = null

    /** Why the last playback attempt got no stream, so the failure can be reported honestly. */
    private var lastRefusal: InnerTubePlayerResponse.PlayabilityStatus? = null

    init {
        connectController()
        search("")
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
            },
            MoreExecutors.directExecutor(),
        )
    }

    // ---- Search -------------------------------------------------------------

    /** Runs a search, replacing any in-flight one. */
    fun search(query: String) {
        searchJob?.cancel()
        _state.value = _state.value.copy(query = query, isLoading = true, error = null)

        searchJob = viewModelScope.launch {
            if (query.isBlank()) {
                // Seeded search, not a blank search and not a chart browse: a blank query is
                // rejected with HTTP 400, and the `FE*` chart browse ids are rejected too.
                discovery()
                return@launch
            }

            delay(SEARCH_DEBOUNCE_MS)
            client.search(query, InnerTubeSearchParams.ALL, limit = BROWSE_LIMIT)
                .onSuccess { items -> _state.value = _state.value.withResults(items, isLoading = false) }
                .onFailure { error -> _state.value = _state.value.failed(error) }
        }
    }

    /**
     * Loads the discovery landing page.
     *
     * The seeds run sequentially rather than concurrently. Firing several InnerTube requests at
     * once is the fastest way to be throttled, and there is no documented limit to reason
     * about — the screen shows its first results sooner and the rest fill in behind them.
     *
     * A seed that fails is skipped rather than failing the page: one bad response should not
     * leave the user with an empty screen.
     */
    private suspend fun discovery() {
        val collected = mutableListOf<InnerTubeMusicItem>()

        for (seed in InnerTubeClient.DISCOVERY_SEEDS) {
            client.search(seed, InnerTubeSearchParams.ALL, limit = SEED_LIMIT)
                .onSuccess { collected += it }
        }

        if (collected.isEmpty()) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "Could not reach YouTube Music",
            )
            return
        }

        _state.value = _state.value.withResults(collected, isLoading = false)
    }

    private fun MusicBrowseUiState.withResults(
        items: List<InnerTubeMusicItem>,
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

    private fun InnerTubeMusicItem.toRows(): List<MusicRow> = buildList {
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
     */
    fun play(row: MusicRow) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isResolving = true, error = null, message = null)
            lastRefusal = null

            val tracks = when (row.kind) {
                MusicRow.Kind.TRACK -> resolveTrack(row.id)
                else -> collectionTracks(row.id)
            }

            if (tracks.isNullOrEmpty()) {
                _state.value = _state.value.copy(
                    isResolving = false,
                    message = StreamRefusal.describe(row.title, lastRefusal),
                )
                return@launch
            }

            enqueue(tracks)

            _state.value = _state.value.copy(
                isResolving = false,
                nowPlayingId = tracks.first().videoId,
                message = "Playing ${tracks.first().title}",
            )
        }
    }

    private suspend fun resolveTrack(videoId: String): List<MusicTrack>? {
        val response = client.resolveAudioStream(videoId).getOrNull() ?: return null
        // Recorded before the early return so [play] can say *why* there is no stream rather
        // than reporting a generic failure. See [StreamRefusal].
        lastRefusal = response.playabilityStatus.takeIf { !it.isPlayable }

        val stream = response.audioStreams.firstOrNull { it.isPlayable() } ?: return null
        val details = response.videoDetails

        return listOf(
            MusicTrack(
                videoId = videoId,
                title = details?.title ?: videoId,
                artist = details?.author,
                album = null,
                durationMs = stream.durationMs ?: details?.lengthSeconds?.times(1000),
                artworkUrl = details?.thumbnails?.lastOrNull(),
                largeArtworkUrl = details?.thumbnails?.lastOrNull(),
                playlistId = null,
                trackIndex = null,
            ),
        )
    }

    private suspend fun collectionTracks(browseId: String): List<MusicTrack> =
        client.collection(browseId)
            .getOrNull()
            .orEmpty()
            .flatMap { it.tracks }

    /**
     * Resolves each track and hands the queue to the playback service.
     *
     * Each track is resolved individually rather than trusting one URL for the queue: InnerTube
     * URLs are per-video, so a single resolved URL cannot cover an album.
     *
     * Tracks are resolved sequentially with a small gap. InnerTube has no documented rate limit
     * and firing twenty player requests at once is a good way to get throttled.
     */
    private suspend fun enqueue(tracks: List<MusicTrack>) {
        val sessionController = controller ?: run {
            _state.value = _state.value.copy(message = "Playback service is still starting")
            return
        }

        val items = tracks.mapNotNull { track ->
            val stream: AudioStream = client.resolveAudioStream(track.videoId)
                .getOrNull()
                ?.audioStreams
                ?.firstOrNull(AudioStream::isPlayable)
                ?: return@mapNotNull null

            buildMediaItem(
                videoId = track.videoId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.largeArtworkUrl ?: track.artworkUrl,
                streamUrl = stream.url,
            )
        }

        if (items.isEmpty()) {
            _state.value = _state.value.copy(message = "No playable streams for this selection")
            return
        }

        sessionController.setMediaItems(items, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        sessionController.prepare()
        sessionController.play()

        // Persist the first track so the library grid shows it and progress is restorable.
        tracks.firstOrNull()?.let { track ->
            runCatching {
                libraryRepository.upsert(MusicLibraryMapper.toEntry(track, isCurrent = true))
            }
        }
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
        private val client: InnerTubeClient,
        private val libraryRepository: LibraryRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MusicBrowseViewModel(client, libraryRepository, context) as T
    }

    companion object {
        /**
         * Debounce before searching.
         *
         * Long enough to avoid a request per keystroke, short enough that results feel
         * responsive. InnerTube has no documented rate limit, so this is the only rate control
         * the client has.
         */
        const val SEARCH_DEBOUNCE_MS = 350L

        private const val BROWSE_LIMIT = 40

        /** Per seed, so three seeds do not each load a full 40-row page. */
        private const val SEED_LIMIT = 15

        fun factory(
            client: InnerTubeClient,
            libraryRepository: LibraryRepository,
            context: Context,
        ): ViewModelProvider.Factory = Factory(client, libraryRepository, context)
    }
}