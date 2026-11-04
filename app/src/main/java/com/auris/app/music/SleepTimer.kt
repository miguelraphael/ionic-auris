package com.auris.app.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owned by the playback service, so closing Now Playing cannot cancel the timer. */
internal class SleepTimer(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val onDeadlineChanged: (Long) -> Unit,
    private val onExpire: () -> Unit,
) {
    private var job: Job? = null

    fun set(durationMs: Long) {
        job?.cancel()
        onDeadlineChanged(if (durationMs > 0) nowMs() + durationMs else 0L)
        if (durationMs > 0) job = scope.launch {
            delay(durationMs)
            onExpire()
            onDeadlineChanged(0L)
        }
    }
}

/** Empty fields mean zero; reject invalid ranges before sending a service command. */
internal fun customSleepDurationMs(hours: String, minutes: String, seconds: String): Long? {
    fun number(value: String): Int? = if (value.isEmpty()) 0 else value.toIntOrNull()
    val h = number(hours)?.takeIf { it in 0..23 } ?: return null
    val m = number(minutes)?.takeIf { it in 0..59 } ?: return null
    val s = number(seconds)?.takeIf { it in 0..59 } ?: return null
    return ((h * 3600L + m * 60L + s) * 1000).takeIf { it > 0 }
}
