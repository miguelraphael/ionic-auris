package com.auris.app.ui.player

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.auris.app.music.MusicPlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One row in the now-playing queue. */
data class PlayerQueueItem(
    val index: Int,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val isCurrent: Boolean,
)

/**
 * Drives the now-playing screen from the live [MediaController].
 *
 * ### Why a controller and not the service's player
 * The player belongs to [MusicPlaybackService] so it survives the Activity. A controller is the
 * only supported way to observe it, and it is also what makes the screen survive rotation with
 * playback untouched.
 *
 * ### State is pulled, not pushed
 * [Player.Listener] tells us *that* something changed; the position still has to be sampled.
 * [publish] therefore runs on every event and on a half-second tick, which is why the seek bar
 * moves while playing without the service having to emit progress.
 */
class PlayerViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<PlayerQueueItem>>(emptyList())
    val queue: StateFlow<List<PlayerQueueItem>> = _queue.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish()
        }
    }

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(
            appContext,
            ComponentName(appContext, MusicPlaybackService::class.java),
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    _state.value = _state.value.copy(
                        error = "Could not connect to the playback service",
                    )
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                publish()
                startTicker()
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * Samples the controller into [PlayerUiState].
     *
     * Called from the listener and from the tick. Safe to call before the controller connects;
     * it is a no-op until then.
     */
    private fun publish() {
        val player = controller ?: return
        val metadata = player.mediaMetadata

        _state.value = PlayerUiState(
            entryId = player.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artworkUrl = metadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            volume = player.volume,
            repeatMode = RepeatMode.fromExoPlayer(player.repeatMode),
            shuffleEnabled = player.shuffleModeEnabled,
            queueSize = player.mediaItemCount,
            queuePosition = player.currentMediaItemIndex,
        )

        _queue.value = if (player.mediaItemCount == 0) {
            emptyList()
        } else {
            (0 until player.mediaItemCount).map { index ->
                val item = player.getMediaItemAt(index)
                val itemMetadata = item.mediaMetadata
                PlayerQueueItem(
                    index = index,
                    title = itemMetadata.title?.toString().orEmpty(),
                    artist = itemMetadata.artist?.toString(),
                    artworkUrl = itemMetadata.artworkUri?.toString(),
                    isCurrent = index == player.currentMediaItemIndex,
                )
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                publish()
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    // ---- Transport ----------------------------------------------------------

    fun togglePlayPause() {
        val player = controller ?: return
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_IDLE -> {
                player.prepare()
                player.play()
            }
            else -> player.play()
        }
    }

    /** Seeks to [fraction] of the track, as reported by the seek bar. */
    fun seekToFraction(fraction: Float) {
        val player = controller ?: return
        val duration = player.duration
        if (duration <= 0L) return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    fun skipBackward() {
        val player = controller ?: return
        player.seekTo((player.currentPosition - SKIP_BACK_MS).coerceAtLeast(0L))
    }

    fun skipForward() {
        val player = controller ?: return
        val target = player.currentPosition + SKIP_FORWARD_MS
        val duration = player.duration
        player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
    }

    fun previous() {
        val player = controller ?: return
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0L)
    }

    fun next() {
        val player = controller ?: return
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    fun cycleRepeat() {
        val player = controller ?: return
        player.repeatMode = when (RepeatMode.fromExoPlayer(player.repeatMode)) {
            RepeatMode.OFF -> Player.REPEAT_MODE_ALL
            RepeatMode.ALL -> Player.REPEAT_MODE_ONE
            RepeatMode.ONE -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun playQueueItem(index: Int) {
        val player = controller ?: return
        if (index in 0 until player.mediaItemCount) player.seekToDefaultPosition(index)
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    /** Reports a control the build does not implement, rather than silently doing nothing. */
    fun unavailable(control: String) {
        _state.value = _state.value.copy(message = "$control is not available yet")
    }

    override fun onCleared() {
        ticker?.cancel()
        ticker = null
        controller?.release()
        controller = null
        super.onCleared()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlayerViewModel(context) as T
    }

    companion object {
        private const val PROGRESS_TICK_MS = 500L
        private const val SKIP_BACK_MS = 10_000L
        private const val SKIP_FORWARD_MS = 30_000L

        fun factory(context: Context): ViewModelProvider.Factory = Factory(context)
    }
}
