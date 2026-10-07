package com.auris.app.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * A bounded in-memory ring buffer of log lines.
 *
 * ### Why this exists
 * `logcat` only retains the last few thousand lines across the whole system. By the time a user
 * notices something wrong and taps "Generate Logs", the interesting lines — the failed source
 * load, the extension parse exception — have usually been evicted by everything else logging on
 * the device. This buffer keeps the app's own history so an export is not just "what is happening
 * now".
 *
 * ### Bounded, deliberately
 * The buffer is fixed-size and evicts oldest-first. An unbounded buffer would grow until it
 * triggered a `TransactionTooLargeException` or an OOM — and a logging facility that can crash
 * the app it is diagnosing is worse than no logging at all.
 *
 * ### Thread-safe
 * [ArrayDeque] is not thread-safe, so every access is synchronised. Logging happens on whatever
 * thread failed, which is frequently a background thread.
 */
class AurisLogBuffer(
    /** Maximum retained lines. */
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lines = ArrayDeque<String>(capacity)
    private val lock = Any()

    /** Lines dropped because the buffer was full. Surfaced in the export so gaps are visible. */
    private val dropped = AtomicInteger(0)

    fun size(): Int = synchronized(lock) { lines.size }

    /** Appends a formatted line, evicting the oldest if full. */
    fun append(level: Char, tag: String, message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$stamp $level/$tag: $message"

        synchronized(lock) {
            if (lines.size >= capacity) {
                lines.pollFirst()
                dropped.incrementAndGet()
            }
            lines.addLast(line)
        }

        // Mirror to logcat too, so a user with `adb` attached sees the same thing the export
        // would contain.
        when (level) {
            'E' -> Log.e(tag, message)
            'W' -> Log.w(tag, message)
            'I' -> Log.i(tag, message)
            'D' -> Log.d(tag, message)
            else -> Log.v(tag, message)
        }
    }

    /** Copies out the buffer in chronological order, oldest first. */
    fun drain(): List<String> = synchronized(lock) { lines.toList() }

    /** How many lines were evicted. Included in the export so a truncated log is not mistaken for a complete one. */
    fun droppedCount(): Int = dropped.get()

    fun clear() {
        synchronized(lock) {
            lines.clear()
            dropped.set(0)
        }
    }

    companion object {
        /**
         * Retained lines.
         *
         * 2000 is a few hundred KB of text and covers several minutes of a chatty session. The
         * cap matters more than the exact number: too large and the buffer competes with image
         * decoding for heap, too small and the interesting failure scrolls away.
         */
        const val DEFAULT_CAPACITY = 2_000

        /** Process-wide instance, since `android.util.Log` has no injectable sink. */
        val Global = AurisLogBuffer()
    }
}

/**
 * The buffer's live state, so the diagnostics screen can show what a capture would contain.
 *
 * Without this, a user taps "Generate Logs" and learns nothing about whether the button worked
 * until after the share sheet has already opened.
 */
class LogBufferState(private val buffer: AurisLogBuffer) {
    private val _revision = MutableStateFlow(0)

    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun noteChange() {
        _revision.value += 1
    }
}