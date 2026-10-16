package com.auris.app.ui.music

import com.auris.app.data.remote.music.StreamRefusalInfo

/**
 * Why a playback attempt produced no stream.
 *
 * ### The distinction this exists to preserve
 * "No audio" has three different causes and they call for three different actions:
 *
 * 1. **The track cannot be played** — private, removed, region-blocked. Choosing another song is
 *    the only remedy, and retrying is wasted effort.
 * 2. **A provider answered but declined** — YouTube refuses unauthenticated first-party requests
 *    outright (verified 2026-10), and a public Invidious instance can answer that it has no
 *    formats for a track. Retrying may work later, but not against the same provider.
 * 3. **No provider answered** — every configured instance was unreachable, or none is configured
 *    at all. This is a *settings* problem, and telling the user the track is unavailable would
 *    send them looking for a different song when the fix is in Settings.
 *
 * Collapsing these into "Nothing playable" is wrong in all three cases, so they are kept apart.
 * The split between 1 and 2 is by whether the reason names content — "private", "removed",
 * "not available" — and the split for 3 is that there was no refusal at all, only an error.
 */
object StreamRefusal {

    /**
     * Reason fragments that describe the track rather than the request.
     *
     * Matched as substrings because providers phrase these freely ("This video is private",
     * "Video unavailable in your country"). Order and breadth matter more than precision: a
     * reason wrongly classified as per-track sends the user to pick another song, while one
     * wrongly classified as a provider failure sends them to retry something that never will.
     */
    private val CONTENT_REASONS = listOf(
        "private",
        "removed",
        "terminated",
        "unavailable in your country",
        "not available",
        "copyright",
        "members-only",
        "age-restricted",
    )

    /** Statuses that describe a per-track condition at all. */
    private val CONTENT_STATUSES = setOf("UNPLAYABLE", "ERROR")

    /**
     * Whether [refusal] means the track itself cannot be played.
     *
     * False for a null refusal, which means no provider gave an answer and the failure is
     * somewhere else entirely — reporting that as a track problem would be a guess.
     */
    fun isAboutContent(refusal: StreamRefusalInfo?): Boolean {
        val status = refusal?.status ?: return false
        val reason = refusal.reason ?: return false
        return status in CONTENT_STATUSES &&
            CONTENT_REASONS.any { reason.contains(it, ignoreCase = true) }
    }

    /**
     * The message shown when playback could not start.
     *
     * @param title the track the user tapped, so the message names what they asked for
     * @param refusal what the provider said, or null when it never answered
     * @param error why no provider answered, when that is what happened
     */
    fun describe(
        title: String,
        refusal: StreamRefusalInfo?,
        error: String? = null,
    ): String = when {
        refusal == null && error != null ->
            "Could not reach an audio source for \"$title\" ($error)"

        refusal == null -> "Could not start \"$title\""

        isAboutContent(refusal) -> "\"$title\" is unavailable: ${refusal.reason}"

        // A provider answered and declined, but the reason says nothing about the track — so the
        // status is quoted rather than interpreted, and the user is not told the song is gone.
        else -> "No audio was served for \"$title\" (${refusal.status})"
    }
}
