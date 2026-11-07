package com.auris.app.source.repo

import org.junit.Assert.*
import org.junit.Test

class ExtensionInstallGuardTest {
    @Test fun duplicateInstallCannotReplaceFileWhileApprovalPending() {
        val guard = ExtensionInstallGuard()
        assertTrue(guard.acquire("publisher.extension"))
        assertFalse(guard.acquire("publisher.extension"))
        assertTrue(guard.acquire("other.extension"))
        guard.release("publisher.extension")
        assertTrue(guard.acquire("publisher.extension"))
    }

    @Test fun concurrentAttemptsHaveExactlyOneWriter() {
        val guard = ExtensionInstallGuard()
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..12).map {
            Thread {
                start.await()
                if (guard.acquire("same.extension")) winners.incrementAndGet()
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }
}
