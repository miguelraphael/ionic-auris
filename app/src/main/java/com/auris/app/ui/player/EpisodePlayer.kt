package com.auris.app.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.auris.app.source.StreamTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Owns the [ExoPlayer] used for episode playback.
 *
 * ### One player per episode, not per composition
 * ExoPlayer holds a codec, an audio focus request and a wake lock, so it must not be created in a
 * composable. But it is also *recreated per episode*, which is unusual enough to explain:
 *
 * Extension streams routinely need request headers — a `Referer`, a cookie, a bearer token —
 * and media3 has **no per-item HTTP header API**. `MediaItem.RequestMetadata` carries only the
 * media URI, a search query and extras, and `ExoPlayer` has no `setMediaSourceFactory`, so
 * headers can only be baked into the `HttpDataSource.Factory` at construction. Playing episode
 * B after episode A therefore requires a new player.
 *
 * The alternative — one player, headers applied globally — would send episode A's credentials to
 * episode B's host, which is both a correctness bug and a credential leak. The cost is a codec
 * re-initialise on episode switch, which is seconds of work and only happens on a user action.
 *
 * [player] is a [StateFlow] so the UI follows whichever instance is current rather than holding a
 * reference captured at composition time.
 *
 * ### Stream format
 * [StreamTarget.mimeType] is honoured when supplied, because it distinguishes HLS (`.m3u8`) from
 * DASH (`.mpd`) from progressive, and guessing from the URL alone is unreliable — many sources
 * serve HLS behind a `.php` path.
 */
@OptIn(UnstableApi::class)
class EpisodePlayer(
    private val context: Context,
    private val httpClient: OkHttpClient = httpClient(),
) : DefaultLifecycleObserver {

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _isBuffering = MutableStateFlow(true)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    /**
     * The last playback error, surfaced for the UI to show once.
     *
     * Kept as a value rather than a callback so this class has no dependency on Compose. The UI
     * must call [consumeError] after displaying it or it reappears.
     */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Plays [targets], using the first entry.
     *
     * @return the player, or null when nothing was playable.
     */
    fun play(targets: List<StreamTarget>): ExoPlayer? {
        val target = targets.firstOrNull()
        if (target == null || target.url.isBlank()) {
            _error.value = "No playable stream for this episode"
            return null
        }

        // Release before rebuilding: two live players would both hold a codec and fight over
        // audio focus.
        release()

        val exo = build(target.headers)
        _player.value = exo
        _isBuffering.value = true

        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(target.url)
                .setMimeType(target.mimeType ?: inferMimeType(target.url))
                .build(),
            /* startPositionMs = */ 0L,
        )
        exo.prepare()
        exo.playWhenReady = true
        return exo
    }

    /**
     * Builds a player for one stream.
     *
     * Buffering is tuned for what sources actually serve: extension hosts are frequently slow, so
     * the defaults — which optimise for start-up latency — cause repeated stalls mid-episode.
     * 30s of buffer rides out a hiccup without making the first frame feel late.
     */
    private fun build(headers: Map<String, String>): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    OkHttpDataSource.Factory(httpClient)
                        .setDefaultRequestProperties(headers),
                ),
            )
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 30_000,
                        /* bufferForPlaybackMs = */ 2_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 5_000,
                    )
                    .build(),
            )
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
            .also {
                it.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            _isBuffering.value = state == Player.STATE_BUFFERING
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            _isBuffering.value = false
                            _error.value = error.toUserMessage()
                        }
                    },
                )
            }

    /** Releases the current player. Safe when none exists. */
    fun release() {
        _player.value?.release()
        _player.value = null
    }

    /** Returns and clears the pending error, so it is shown exactly once. */
    fun consumeError(): String? = _error.value.also { _error.value = null }

    // ---- Lifecycle ----------------------------------------------------------

    /**
     * Pauses when the app is backgrounded.
     *
     * `onStop` rather than `onPause`: `onPause` also fires for transient interruptions — a
     * dialog, a notification shade pull — and pausing a video for those is wrong.
     *
     * Playback is *paused*, not stopped, so returning to the app resumes at the same position
     * with the buffer intact rather than re-fetching from the start.
     */
    override fun onStop(owner: LifecycleOwner) {
        _player.value?.playWhenReady = false
    }

    override fun onDestroy(owner: LifecycleOwner) {
        release()
    }

    // ---- Errors -------------------------------------------------------------

    private fun PlaybackException.toUserMessage(): String = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Network error while streaming. Check your connection."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            // Usually a missing Referer or an expired token — the two things extensions hit most.
            "The source rejected the request. It may need a fresh stream link."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> "This stream format is not supported."

        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
            "This episode is DRM-protected and cannot be played."

        else -> "Playback failed: ${message ?: "error $errorCode"}"
    }

    /**
     * Infers a MIME type from the URL when the extension supplied none.
     *
     * ExoPlayer would sniff the container itself, but stating the type avoids a wasted request
     * and the visible stall that sniffing causes on manifests, which is where it hurts most.
     */
    private fun inferMimeType(url: String): String? = when {
        url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
        url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
        url.contains(".m4s", ignoreCase = true) -> MimeTypes.APPLICATION_MP4
        url.contains(".webm", ignoreCase = true) -> MimeTypes.VIDEO_WEBM
        else -> null
    }

    companion object {
        /** Matches the skip increments on the music player's controls. */
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 10_000L

        /**
         * HTTP client for extension media.
         *
         * Deliberately *separate* from the app's own client: streams are long-lived, so sharing
         * a pool with the Kitsu and repository calls means a buffering episode holds connections
         * that short API requests need. Timeouts are generous because extension hosts are slow.
         */
        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}