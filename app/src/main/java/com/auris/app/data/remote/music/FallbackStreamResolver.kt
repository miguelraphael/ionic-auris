package com.auris.app.data.remote.music

import java.io.IOException

/**
 * Tries [primary], then [secondary], and decides when asking twice is worth it.
 *
 * ### Why not "retry on any failure"
 * A provider's answer means different things, and only one of them is worth another provider's
 * time:
 *
 * - **A stream** — done.
 * - **A content refusal** ("private", "not available in your country") — the track itself is the
 *   problem. A second provider would either repeat the answer or, worse, appear to fix it by
 *   finding a copy the user was not meant to have. Returned as-is.
 * - **[StreamRefusalInfo.UNFETCHABLE]** — the provider found the track and named a stream, but
 *   *this device* could not fetch it. That is a property of how the first provider minted its URLs,
 *   not of the track, so it is exactly the case a second provider can fix.
 * - **A failure** — the provider never answered (unreachable, rate-limited, changed its API). The
 *   classic failover case.
 *
 * When the second provider also fails, an answer from the first is returned if there was one: it
 * tells the user something true about the track, while "no provider answered" would send them to
 * Settings for a network problem that is not there.
 */
class FallbackStreamResolver(
    private val primary: MusicStreamResolver,
    private val secondary: MusicStreamResolver,
) : MusicStreamResolver {

    override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> {
        val first = primary.resolveAudioStream(videoId)
        val answered = first.getOrNull()

        if (answered?.isPlayable == true) return first
        if (answered?.refusal != null &&
            answered.refusal.status != StreamRefusalInfo.UNFETCHABLE
        ) {
            return first
        }

        val second = secondary.resolveAudioStream(videoId)

        return when {
            second.isSuccess -> second

            // An answer — even "the extractor could not deliver this one" — beats no answer.
            answered?.refusal != null -> first

            first.isFailure && second.isFailure -> Result.failure(
                IOException(
                    listOfNotNull(first.exceptionOrNull()?.message, second.exceptionOrNull()?.message)
                        .joinToString("; ")
                        .ifBlank { "No audio source could resolve this track" },
                ),
            )

            else -> second
        }
    }
}
