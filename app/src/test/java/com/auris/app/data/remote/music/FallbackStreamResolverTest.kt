package com.auris.app.data.remote.music

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the policy between two resolvers, not either resolver.
 *
 * The interesting question is not "can it retry" but "when is a retry worth another provider's
 * request": a content refusal and a delivery refusal look alike in a `Result` and must not be
 * treated alike.
 */
class FallbackStreamResolverTest {

    private class FakeResolver(
        private val answer: () -> Result<ResolvedAudio>,
    ) : MusicStreamResolver {
        var calls = 0
            private set

        override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> {
            calls++
            return answer()
        }
    }

    private fun playing(url: String = "https://media.example/track") = Result.success(
        ResolvedAudio(
            stream = AudioStream(
                url = url,
                mimeType = "audio/mp4",
                bitrate = 128,
                durationMs = 1_000,
                expiresAtMs = null,
            ),
            title = "Track",
            author = "Artist",
            lengthMs = 1_000,
        ),
    )

    private fun refusal(status: String, reason: String = "refused") = Result.success(
        ResolvedAudio(refusal = StreamRefusalInfo(status, reason), title = "Track"),
    )

    private fun resolve(primary: FakeResolver, secondary: FakeResolver) = runBlocking {
        FallbackStreamResolver(primary, secondary).resolveAudioStream("abc123def45")
    }

    @Test
    fun `a playable stream from the primary ends the attempt`() {
        val primary = FakeResolver { playing() }
        val secondary = FakeResolver { playing("https://media.example/other") }

        val result = resolve(primary, secondary)

        assertTrue(result.getOrNull()?.isPlayable == true)
        assertEquals(1, primary.calls)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `a content refusal is an answer and is not retried`() {
        val primary = FakeResolver { refusal("UNPLAYABLE", "This video is private") }
        val secondary = FakeResolver { playing() }

        val result = resolve(primary, secondary)

        assertEquals("This video is private", result.getOrNull()?.refusal?.reason)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `an unfetchable stream falls through to the secondary`() {
        val primary = FakeResolver { refusal(StreamRefusalInfo.UNFETCHABLE) }
        val secondary = FakeResolver { playing() }

        val result = resolve(primary, secondary)

        assertTrue(result.getOrNull()?.isPlayable == true)
        assertEquals(1, secondary.calls)
    }

    @Test
    fun `an unfetchable primary keeps its refusal when the secondary does not answer`() {
        val primary = FakeResolver { refusal(StreamRefusalInfo.UNFETCHABLE) }
        val secondary = FakeResolver { Result.failure(IOException("unreachable")) }

        val result = resolve(primary, secondary)

        assertEquals(StreamRefusalInfo.UNFETCHABLE, result.getOrNull()?.refusal?.status)
        assertEquals(1, secondary.calls)
    }

    @Test
    fun `a failed primary falls through to the secondary`() {
        val primary = FakeResolver { Result.failure(IOException("unreachable")) }
        val secondary = FakeResolver { playing() }

        val result = resolve(primary, secondary)

        assertTrue(result.getOrNull()?.isPlayable == true)
    }

    @Test
    fun `when neither provider answers the failure names both`() {
        val primary = FakeResolver { Result.failure(IOException("extractor broke")) }
        val secondary = FakeResolver { Result.failure(IOException("instance rate-limited")) }

        val result = resolve(primary, secondary)

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("extractor broke"))
        assertTrue(message.contains("instance rate-limited"))
    }

    @Test
    fun `a stream that has expired is not treated as a playable answer`() {
        val primary = FakeResolver {
            Result.success(
                ResolvedAudio(
                    stream = AudioStream(
                        url = "https://media.example/stale",
                        mimeType = "audio/mp4",
                        bitrate = 128,
                        durationMs = 1_000,
                        expiresAtMs = 1,
                    ),
                ),
            )
        }
        val secondary = FakeResolver { playing() }

        val result = resolve(primary, secondary)

        assertEquals("https://media.example/track", result.getOrNull()?.stream?.url)
        assertEquals(1, secondary.calls)
    }
}
