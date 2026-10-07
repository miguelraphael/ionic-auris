package com.auris.app.ui.music

import com.auris.app.data.remote.music.InnerTubePlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a failed playback is explained to the user.
 *
 * ### Why this is worth a rule
 * The live API answers *every* stream request from an unauthenticated client with `UNPLAYABLE`
 * and an empty format list — verified on 2026-10 for `WEB_REMIX` and `ANDROID_MUSIC`, and
 * `FAILED_PRECONDITION` for every other client identity tried. The track exists and is
 * playable; the request is what was declined.
 *
 * The naive message for that state, "Nothing playable", is wrong twice: it implies the track is
 * missing, and it implies a retry might help. Neither holds, and a user told to retry something
 * that can never succeed is being sent in circles. Meanwhile a genuinely delisted track needs the
 * opposite advice — pick something else.
 */
class StreamRefusalTest {

    private fun status(value: String, reason: String?) =
        InnerTubePlayerResponse.PlayabilityStatus(status = value, reason = reason)

    // ---- The live response: a request failure -------------------------------

    @Test
    fun liveUnplayableWithNoTrackReasonIsARequestFailure() {
        // The exact response the app receives, reason included.
        val playability = status("UNPLAYABLE", "Video unavailable")

        assertFalse(StreamRefusal.isAboutContent(playability))
        assertEquals(
            "YouTube declined to serve audio for \"Espresso\" (UNPLAYABLE)",
            StreamRefusal.describe("Espresso", playability),
        )
    }

    @Test
    fun loginRequiredIsARequestFailure() {
        val playability = status("LOGIN_REQUIRED", null)

        assertFalse(StreamRefusal.isAboutContent(playability))
        assertTrue(StreamRefusal.describe("Espresso", playability).contains("declined"))
    }

    @Test
    fun aRefusalMessageDoesNotSayTheTrackIsUnplayable() {
        // The specific wording bug worth pinning: "Nothing playable for Espresso" tells the user
        // their track is at fault when the server declined the request.
        val message = StreamRefusal.describe("Espresso", status("UNPLAYABLE", "Video unavailable"))

        assertFalse(message.contains("Nothing playable"))
        assertTrue(message.contains("declined"))
    }

    // ---- Genuinely per-track reasons ----------------------------------------

    @Test
    fun privateVideoIsAContentFailure() {
        val playability = status("UNPLAYABLE", "This video is private")

        assertTrue(StreamRefusal.isAboutContent(playability))
        assertEquals(
            "\"Discovery\" is unavailable: This video is private",
            StreamRefusal.describe("Discovery", playability),
        )
    }

    @Test
    fun regionBlockedAndRemovedAreContentFailures() {
        assertTrue(StreamRefusal.isAboutContent(status("ERROR", "Video unavailable in your country")))
        assertTrue(StreamRefusal.isAboutContent(status("UNPLAYABLE", "This video has been removed")))
        assertTrue(StreamRefusal.isAboutContent(status("UNPLAYABLE", "Removed for copyright")))
    }

    @Test
    fun aContentMessageCarriesTheServerReason() {
        // The reason is the actionable part — "removed" and "private" need different responses
        // from the user, so it is passed through rather than summarised away.
        val message = StreamRefusal.describe("Track", status("UNPLAYABLE", "This video is private"))

        assertTrue(message.contains("This video is private"))
    }

    // ---- Boundaries ----------------------------------------------------------

    @Test
    fun anAccountLevelReasonIsNotClaimedAsPerTrack() {
        // "Sign in to confirm your age" names no track. Reporting it as a content problem would
        // tell the user to choose a different song for a reason that applies to the whole
        // request.
        val playability = status("LOGIN_REQUIRED", "Sign in to confirm your age")

        assertFalse(StreamRefusal.isAboutContent(playability))
    }

    @Test
    fun aMissingReasonIsNeverTreatedAsAContentFailure() {
        // No reason means nothing can be concluded about the track.
        assertFalse(StreamRefusal.isAboutContent(status("UNPLAYABLE", null)))
    }

    @Test
    fun aMissingPlayabilityStatusIsNotGuessedAt() {
        // Null means the response carried no playability information, so the failure is
        // elsewhere; claiming a refusal would be inventing a diagnosis.
        assertFalse(StreamRefusal.isAboutContent(null))
        assertEquals("Could not start \"Track\"", StreamRefusal.describe("Track", null))
    }

    @Test
    fun aShortNumberInAReasonIsNotAYearMatch() {
        // Guards the substring matching from being too eager: a reason that merely contains a
        // digit run must not be classified as a release year or a count.
        val playability = status("UNPLAYABLE", "Error 2019 occurred")

        assertFalse(StreamRefusal.isAboutContent(playability))
    }
}
