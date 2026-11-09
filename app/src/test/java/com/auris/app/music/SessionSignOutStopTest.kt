package com.auris.app.music

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSignOutStopTest {
    @Test fun stopsWhenSessionLostAfterPresence() {
        assertTrue(shouldStopOnSessionChange(hadSession = true, hasSession = false))
    }

    @Test fun coldStartNullDoesNotStop() {
        assertFalse(shouldStopOnSessionChange(hadSession = false, hasSession = false))
    }

    @Test fun sessionPresentDoesNotStop() {
        assertFalse(shouldStopOnSessionChange(hadSession = true, hasSession = true))
        assertFalse(shouldStopOnSessionChange(hadSession = false, hasSession = true))
    }
}
