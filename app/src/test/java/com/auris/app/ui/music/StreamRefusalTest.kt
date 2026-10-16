package com.auris.app.ui.music

import com.auris.app.data.remote.music.StreamRefusalInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a failed playback is explained to the user.
 *
 * ### Why this is worth a rule
 * "No audio" has three causes that need three different actions, and the naive message —
 * "Nothing playable" — is wrong for all of them:
 *
 * - a **delisted or private track**, where picking another song is the only remedy;
 * - a **provider that declined** the request, where retrying the same provider is pointless
 *   (YouTube refuses unauthenticated first-party requests outright — verified 2026-10);
 * - **no provider reachable at all**, where the fix is in Settings, not in the music list.
 *
 * A user sent to retry something that can never succeed is being sent in circles, so the split is
 * pinned here.
 */
class StreamRefusalTest {

    private fun refusal(value: String, reason: String?) =
        StreamRefusalInfo(status = value, reason = reason)

    // ---- A provider declined ------------------------------------------------

    @Test
    fun liveUnplayableWithNoTrackReasonIsAProviderFailure() {
        // The exact response an unauthenticated first-party request receives, reason included.
        val playability = refusal("UNPLAYABLE", "Video unavailable")

        assertFalse(StreamRefusal.isAboutContent(playability))
        assertEquals(
            "No audio was served for \"Espresso\" (UNPLAYABLE)",
            StreamRefusal.describe("Espresso", playability),
        )
    }

    @Test
    fun loginRequiredIsARequestFailure() {
        val playability = refusal("LOGIN_REQUIRED", null)

        assertFalse(StreamRefusal.isAboutContent(playability))
        assertTrue(StreamRefusal.describe("Espresso", playability).contains("No audio was served"))
    }

    @Test
    fun aProviderRefusalDoesNotSayTheTrackIsUnplayable() {
        // The specific wording bug worth pinning: telling the user their track is at fault when
        // the provider declined the *request*.
        val message = StreamRefusal.describe("Espresso", refusal("UNPLAYABLE", "Video unavailable"))

        assertFalse(message.contains("Nothing playable"))
        assertTrue(message.contains("No audio was served"))
    }

    @Test
    fun anInstanceWithNoFormatsQuotesTheStatusWithoutBlamingTheTrack() {
        // What a public instance actually answers when it has no audio for a track. The reason
        // names no content, so the user must not be told the song is gone.
        val message = StreamRefusal.describe(
            "Espresso",
            refusal("UNPLAYABLE", "The instance returned no playable audio format"),
        )

        assertFalse(StreamRefusal.isAboutContent(
            refusal("UNPLAYABLE", "The instance returned no playable audio format"),
        ))
        assertEquals("No audio was served for \"Espresso\" (UNPLAYABLE)", message)
    }

    // ---- Genuinely per-track reasons ----------------------------------------

    @Test
    fun privateVideoIsAContentFailure() {
        val playability = refusal("UNPLAYABLE", "This video is private")

        assertTrue(StreamRefusal.isAboutContent(playability))
        assertEquals(
            "\"Discovery\" is unavailable: This video is private",
            StreamRefusal.describe("Discovery", playability),
        )
    }

    @Test
    fun regionBlockedAndRemovedAreContentFailures() {
        assertTrue(StreamRefusal.isAboutContent(refusal("ERROR", "Video unavailable in your country")))
        assertTrue(StreamRefusal.isAboutContent(refusal("UNPLAYABLE", "This video has been removed")))
        assertTrue(StreamRefusal.isAboutContent(refusal("UNPLAYABLE", "Removed for copyright")))
        // The phrasing a public instance uses when the video is gone.
        assertTrue(StreamRefusal.isAboutContent(refusal("UNPLAYABLE", "This video is not available")))
    }

    @Test
    fun aContentMessageCarriesTheProviderReason() {
        // The reason is the actionable part — "removed" and "private" need different responses
        // from the user, so it is passed through rather than summarised away.
        val message = StreamRefusal.describe("Track", refusal("UNPLAYABLE", "This video is private"))

        assertTrue(message.contains("This video is private"))
    }

    // ---- No provider answered at all ----------------------------------------

    @Test
    fun noProviderAnsweringPointsAtTheSourceNotTheTrack() {
        // Every configured instance was unreachable. Telling the user the track is unavailable
        // would send them hunting for another song when the fix is in Settings.
        val message = StreamRefusal.describe(
            title = "Espresso",
            refusal = null,
            error = "No Invidious instance could serve this track",
        )

        assertTrue(message.startsWith("Could not reach an audio source"))
        assertTrue(message.contains("No Invidious instance could serve this track"))
        assertFalse(message.contains("unavailable"))
    }

    // ---- Boundaries ----------------------------------------------------------

    @Test
    fun anAccountLevelReasonIsNotClaimedAsPerTrack() {
        // "Sign in to confirm your age" names no track. Reporting it as a content problem would
        // tell the user to choose a different song for a reason that applies to the whole
        // request.
        val playability = refusal("LOGIN_REQUIRED", "Sign in to confirm your age")

        assertFalse(StreamRefusal.isAboutContent(playability))
    }

    @Test
    fun aMissingReasonIsNeverTreatedAsAContentFailure() {
        // No reason means nothing can be concluded about the track.
        assertFalse(StreamRefusal.isAboutContent(refusal("UNPLAYABLE", null)))
    }

    @Test
    fun aMissingRefusalIsNotGuessedAt() {
        // Null means no provider gave an answer, so the failure is elsewhere; claiming a refusal
        // would be inventing a diagnosis.
        assertFalse(StreamRefusal.isAboutContent(null))
        assertEquals("Could not start \"Track\"", StreamRefusal.describe("Track", null))
        // With an error and no refusal, the error is what is reported.
        assertEquals(
            "Could not reach an audio source for \"Track\" (timeout)",
            StreamRefusal.describe("Track", null, "timeout"),
        )
    }

    @Test
    fun aShortNumberInAReasonIsNotAYearMatch() {
        // Guards the substring matching from being too eager: a reason that merely contains a
        // digit run must not be classified as a release year or a count.
        val playability = refusal("UNPLAYABLE", "Error 2019 occurred")

        assertFalse(StreamRefusal.isAboutContent(playability))
    }
}
