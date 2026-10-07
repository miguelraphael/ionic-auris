package com.auris.app.ui.music

import com.auris.app.data.remote.music.InnerTubePlayerResponse

/**
 * Why a playback attempt produced no stream.
 *
 * ### The distinction this exists to preserve
 * InnerTube answers every stream request from an unauthenticated client with `UNPLAYABLE` and an
 * empty format list — the track is present and playable, the *request* is what was declined. That
 * is verified live for `WEB_REMIX`, `ANDROID_MUSIC` and every other client identity tried.
 *
 * The naive message for that state is "Nothing playable", which is wrong twice: it implies the
 * track is missing, and it implies retrying might help. Neither is true. The user needs to be
 * told the request was refused, because the only fix is a different resolution strategy.
 *
 * The split is by whether `playabilityStatus.reason` describes *this track*. A reason naming the
 * content — private, removed, region-blocked — will never work and choosing another song is the
 * remedy. Anything else is about the request.
 */
object StreamRefusal {

    /**
     * `playabilityStatus.reason` fragments that describe the track rather than the request.
     *
     * Matched as substrings because YouTube phrases these freely ("This video is private",
     * "Video unavailable in your country"). Order and breadth matter more than precision here:
     * a reason wrongly classified as per-track sends the user to pick another song, while one
     * wrongly classified as a request failure sends them to retry something that never will.
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
     * Whether [playability] means the track itself cannot be played.
     *
     * False for a null status, which means the response carried no playability information and
     * the failure is somewhere else entirely — reporting that as a refusal would be a guess.
     */
    fun isAboutContent(playability: InnerTubePlayerResponse.PlayabilityStatus?): Boolean {
        val status = playability?.status ?: return false
        val reason = playability.reason ?: return false
        return status in CONTENT_STATUSES &&
            CONTENT_REASONS.any { reason.contains(it, ignoreCase = true) }
    }

    /**
     * The message shown when playback could not start.
     *
     * @param title the track the user tapped, so the message names what they asked for
     * @param playability what the server said, or null when there was no playability status
     */
    fun describe(title: String, playability: InnerTubePlayerResponse.PlayabilityStatus?): String =
        when {
            playability == null -> "Could not start \"$title\""
            isAboutContent(playability) ->
                "\"$title\" is unavailable: ${playability.reason}"

            else ->
                "YouTube declined to serve audio for \"$title\" (${playability.status})"
        }
}
