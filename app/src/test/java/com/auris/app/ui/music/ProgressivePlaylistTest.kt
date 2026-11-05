package com.auris.app.ui.music

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProgressivePlaylistTest {
    @Test fun startsBeforeSlowRemainingTracksAndPreservesOrder() = runBlocking {
        val remaining = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val queue = mutableListOf<String>()
        val job = launch {
            startProgressivePlaylist(listOf("a", "b", "c"), 1,
                resolve = { if (it != "b") remaining.await(); it },
                start = { queue.add(it); started.complete(Unit) },
                insert = { index, track -> queue.add(index.coerceAtMost(queue.size), track) })
        }
        withTimeout(1000) { started.await() }
        assertEquals(listOf("b"), queue)
        remaining.complete(Unit)
        job.join()
        assertEquals(listOf("a", "b", "c"), queue)
    }

    @Test fun unavailableSelectedSongDoesNotStartAnotherSong() = runBlocking {
        var started = false
        try {
            startProgressivePlaylist(listOf("a", "b"), 0,
                resolve = { null as String? }, start = { started = true }, insert = { _, _ -> })
            fail("Expected selected-track failure")
        } catch (_: IllegalStateException) { }
        assertFalse(started)
    }

    @Test fun cancellationStopsQueuePopulation() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val queue = mutableListOf<String>()
        val job = launch {
            startProgressivePlaylist(listOf("a", "b"), 0,
                resolve = { if (it == "b") awaitCancellation(); it },
                start = { queue.add(it); started.complete(Unit) },
                insert = { _, track -> queue.add(track) })
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(listOf("a"), queue)
    }
}
