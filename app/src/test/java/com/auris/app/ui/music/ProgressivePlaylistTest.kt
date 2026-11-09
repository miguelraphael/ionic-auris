package com.auris.app.ui.music

import androidx.media3.common.Player
import com.auris.app.music.resumeIfEnded
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

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

    @Test fun lateInsertAfterEndedResumesPlayback() {
        // Track 1 ends before item 2 lands: ENDED with a next item must be re-driven.
        val fake = FakePlayer(Player.STATE_ENDED, hasNext = true)
        assertTrue(resumeIfEnded(fake.player))
        assertEquals(1, fake.prepareCalls)
        assertEquals(1, fake.playCalls)
    }

    @Test fun endedPlayerWithoutNextItemIsLeftAlone() {
        val fake = FakePlayer(Player.STATE_ENDED, hasNext = false)
        assertFalse(resumeIfEnded(fake.player))
        assertEquals(0, fake.prepareCalls)
        assertEquals(0, fake.playCalls)
    }

    @Test fun playerThatIsNotEndedIsNotReprepared() {
        val fake = FakePlayer(Player.STATE_READY, hasNext = true)
        assertFalse(resumeIfEnded(fake.player))
        assertEquals(0, fake.prepareCalls)
        assertEquals(0, fake.playCalls)
    }

    /** Minimal Player stand-in: only the state and the resume calls are observed. */
    private class FakePlayer(
        private val playbackState: Int,
        private val hasNext: Boolean,
    ) : InvocationHandler {
        var prepareCalls = 0
        var playCalls = 0
        val player: Player = Proxy.newProxyInstance(
            Player::class.java.classLoader, arrayOf(Player::class.java), this,
        ) as Player

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? =
            when (method.name) {
                "getPlaybackState" -> playbackState
                "hasNextMediaItem" -> hasNext
                "prepare" -> { prepareCalls++; null }
                "play" -> { playCalls++; null }
                else -> defaultFor(method.returnType)
            }

        private fun defaultFor(type: Class<*>): Any? = when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Short::class.javaPrimitiveType -> 0.toShort()
            Byte::class.javaPrimitiveType -> 0.toByte()
            Char::class.javaPrimitiveType -> 0.toChar()
            else -> null
        }
    }
}
