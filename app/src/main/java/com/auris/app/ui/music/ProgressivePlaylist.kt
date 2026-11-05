package com.auris.app.ui.music

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Start the selected track before extracting any other stream. Null resolutions are skipped. */
internal suspend fun <R, T : Any> startProgressivePlaylist(
    rows: List<R>, startIndex: Int,
    resolve: suspend (R) -> T?,
    start: suspend (T) -> Unit,
    insert: (Int, T) -> Unit,
) {
    val selected = rows.getOrNull(startIndex) ?: error("No song selected")
    val first = resolve(selected) ?: error("Could not play this song. Try another song or retry.")
    currentCoroutineContext().ensureActive()
    start(first)
    // Upcoming songs take priority; earlier songs remain available through Previous.
    var after = 1
    for (index in startIndex + 1 until rows.size) {
        val track = resolve(rows[index]) ?: continue
        currentCoroutineContext().ensureActive()
        insert(after++, track)
    }
    var before = 0
    for (index in 0 until startIndex) {
        val track = resolve(rows[index]) ?: continue
        currentCoroutineContext().ensureActive()
        insert(before++, track)
    }
}
