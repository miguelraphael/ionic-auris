package com.auris.app.ui.player

import androidx.compose.runtime.Immutable

/**
 * Everything the music player screen renders. Supplied by `PlayerViewModel`, which reads it
 * from the ExoPlayer `Player.Listener` / `MediaSession` state.
 */
@Immutable
data class PlayerUiState(
    val entryId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUrl: String? = null,

    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,

    /** System media volume as 0..1 — the same stream the hardware keys change. */
    val volume: Float = 1f,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val queueSize: Int = 0,
    val queuePosition: Int = 0,

    /** A transient message from a control that could not act, e.g. an unavailable feature. */
    val message: String? = null,

    /** Set only when the playback service could not be reached at all. */
    val error: String? = null,
) {
    /** True when nothing has ever been queued, which is a different screen from a paused one. */
    val isEmpty: Boolean
        get() = queueSize == 0 && title.isBlank() && error == null
    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val bufferedProgress: Float
        get() = if (durationMs > 0L) {
            (bufferedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

enum class RepeatMode {
    OFF,
    ALL,
    ONE;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromExoPlayer(value: Int): RepeatMode = when (value) {
            androidx.media3.common.Player.REPEAT_MODE_ALL -> ALL
            androidx.media3.common.Player.REPEAT_MODE_ONE -> ONE
            else -> OFF
        }
    }
}

/** mm:ss, or h:mm:ss past an hour. Matches the `0:21` / `3:02` labels in the reference. */
fun Long.toTimestamp(): String {
    if (this <= 0L) return "0:00"
    val totalSeconds = this / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}