package com.auris.app.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {
    @Test
    fun expiresOnceAndClearsDeadline() = runTest {
        var pauses = 0
        var deadline = 0L
        val timer = SleepTimer(this, { testScheduler.currentTime }, { deadline = it }, { pauses++ })
        timer.set(60_000)
        assertEquals(60_000L, deadline)
        advanceTimeBy(59_999)
        assertEquals(0, pauses)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pauses)
        assertEquals(0L, deadline)
    }

    @Test
    fun replacingTimerCancelsOriginalDeadline() = runTest {
        var pauses = 0
        val timer = SleepTimer(this, { testScheduler.currentTime }, {}, { pauses++ })
        timer.set(1_000)
        advanceTimeBy(500)
        timer.set(2_000)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, pauses)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, pauses)
    }

    @Test
    fun turningOffTimerPreventsPause() = runTest {
        var pauses = 0
        var deadline = 0L
        val timer = SleepTimer(this, { testScheduler.currentTime }, { deadline = it }, { pauses++ })
        timer.set(1_000)
        timer.set(0)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, pauses)
        assertEquals(0L, deadline)
    }
    @Test
    fun customDurationSupportsSecondsAndHours() {
        assertEquals(5_000L, customSleepDurationMs("", "", "5"))
        assertEquals(5_415_000L, customSleepDurationMs("1", "30", "15"))
        assertEquals(86_399_000L, customSleepDurationMs("23", "59", "59"))
    }

    @Test
    fun rejectsZeroInvalidAndOverflowingCustomDurations() {
        for ((h, m, s) in listOf(Triple("", "", ""), Triple("0", "0", "0"),
            Triple("24", "0", "0"), Triple("0", "60", "0"), Triple("0", "0", "60"),
            Triple("-1", "0", "0"), Triple("x", "1", "0"), Triple("99999999999999", "0", "0"))) {
            assertNull(customSleepDurationMs(h, m, s))
        }
    }
}
