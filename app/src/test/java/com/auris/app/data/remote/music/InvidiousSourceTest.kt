package com.auris.app.data.remote.music

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the resolver's behaviour *between* instances, against real servers.
 *
 * ### Why these are HTTP-level
 * Everything worth getting wrong here is about requests that do or do not happen: whether a dead
 * instance is retried, whether a cached URL is reused, whether a refusal about one track retires
 * a perfectly healthy host. A parser test cannot see any of that, because none of it reaches a
 * parser.
 */
class InvidiousSourceTest {

    private val servers = mutableListOf<MockWebServer>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun newServer(): MockWebServer = MockWebServer().apply {
        start()
        servers += this
    }

    /** The instance base URL as the store would hold it: no trailing slash. */
    private fun base(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun audioBody(streamUrl: String, title: String = "Track"): String = """
        {"title":"$title","author":"Artist","lengthSeconds":200,
         "videoThumbnails":[{"url":"https://i/art.jpg","width":640}],
         "adaptiveFormats":[
           {"itag":251,"type":"audio/webm; codecs=\"opus\"","bitrate":"160000","url":"$streamUrl"}
         ]}
    """.trimIndent()

    private fun sourceOver(vararg instances: String, clock: () -> Long = System::currentTimeMillis) =
        InvidiousSource(
            httpClient = OkHttpClient(),
            json = json,
            instances = { instances.toList() },
            clock = clock,
        )

    // ---- Failover ------------------------------------------------------------

    @Test
    fun `falls over to the next instance when the first fails`() = runBlocking {
        val dead = newServer()
        val live = newServer()
        dead.enqueue(MockResponse().setResponseCode(403).setBody("Endpoint disabled"))
        live.enqueue(MockResponse().setBody(audioBody("https://cdn.test/ok.webm")))

        val result = sourceOver(base(dead), base(live)).resolveAudioStream("v1")

        assertTrue(result.isSuccess)
        assertEquals("https://cdn.test/ok.webm", result.getOrNull()?.stream?.url)
        // Both were asked: failover means trying, not presuming.
        assertEquals(1, dead.requestCount)
        assertEquals(1, live.requestCount)
    }

    @Test
    fun `metadata arriving with the stream is carried through`() = runBlocking {
        val live = newServer()
        live.enqueue(MockResponse().setBody(audioBody("https://cdn.test/ok.webm", title = "Espresso")))

        val resolved = sourceOver(base(live)).resolveAudioStream("v1").getOrNull()!!

        // The catalogue returns an empty thumbnail array for song rows, so this is the only place
        // the artwork exists.
        assertEquals("Espresso", resolved.title)
        assertEquals("Artist", resolved.author)
        assertEquals("https://i/art.jpg", resolved.artworkUrl)
        assertEquals(200_000L, resolved.stream?.durationMs)
    }

    @Test
    fun `every instance failing is a failure, not a silent success`() = runBlocking {
        val dead = newServer()
        dead.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = sourceOver(base(dead)).resolveAudioStream("v1")

        assertTrue(result.isFailure)
        // The host is named so the message in Settings and the log can be acted on.
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No Invidious instance"))
    }

    @Test
    fun `no configured instance is reported rather than looking like a track problem`() = runBlocking {
        val result = sourceOver().resolveAudioStream("v1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("No Invidious instance is configured"))
    }

    // ---- Refusals are answers, not failures ----------------------------------

    @Test
    fun `an instance saying a track is unavailable is a refusal, not an instance failure`() =
        runBlocking {
            val instance = newServer()
            instance.enqueue(MockResponse().setBody("""{"error":"This video is not available"}"""))

            val result = sourceOver(base(instance)).resolveAudioStream("v1")

            // Success with no stream: the instance answered. Treating this as a host failure
            // would bench a healthy instance every time a delisted track was tapped.
            assertTrue(result.isSuccess)
            assertNull(result.getOrNull()?.stream)
            assertEquals("This video is not available", result.getOrNull()?.refusal?.reason)
        }

    @Test
    fun `an instance offering no formats is a refusal with a reason that blames no track`() =
        runBlocking {
            val instance = newServer()
            instance.enqueue(MockResponse().setBody("""{"title":"T","adaptiveFormats":[]}"""))

            val result = sourceOver(base(instance)).resolveAudioStream("v1")

            assertTrue(result.isSuccess)
            assertNull(result.getOrNull()?.stream)
            assertEquals("The instance returned no playable audio format", result.getOrNull()?.refusal?.reason)
        }

    // ---- Caching -------------------------------------------------------------

    @Test
    fun `a resolved stream is cached so the instance is asked once`() = runBlocking {
        val instance = newServer()
        instance.enqueue(MockResponse().setBody(audioBody("https://cdn.test/ok.webm?expire=9999999999")))

        val source = sourceOver(base(instance))
        source.resolveAudioStream("v1")
        source.resolveAudioStream("v1")

        assertEquals(1, instance.requestCount)
    }

    @Test
    fun `a cached stream is re-resolved once its url expires`() = runBlocking {
        val instance = newServer()
        val now = 1_000_000L
        var clock = now
        val soon = (now + 60_000) / 1000

        instance.enqueue(MockResponse().setBody(audioBody("https://cdn.test/old.webm?expire=$soon")))
        instance.enqueue(MockResponse().setBody(audioBody("https://cdn.test/new.webm?expire=9999999999")))

        val source = sourceOver(base(instance), clock = { clock })

        assertEquals("https://cdn.test/old.webm?expire=$soon", source.resolveAudioStream("v1").getOrNull()?.stream?.url)
        // Still inside its deadline: the cache answers.
        source.resolveAudioStream("v1")
        assertEquals(1, instance.requestCount)

        clock = now + 120_000
        val refreshed = source.resolveAudioStream("v1")

        // Expired, so the instance is asked again — this is the whole reason the URL's `expire`
        // parameter is parsed rather than assumed.
        assertEquals(2, instance.requestCount)
        assertEquals("https://cdn.test/new.webm?expire=9999999999", refreshed.getOrNull()?.stream?.url)
    }

    @Test
    fun `a refusal is not cached, so a retry asks again`() = runBlocking {
        val instance = newServer()
        instance.enqueue(MockResponse().setBody("""{"error":"This video is not available"}"""))
        instance.enqueue(MockResponse().setBody(audioBody("https://cdn.test/ok.webm")))

        val source = sourceOver(base(instance))
        assertNull(source.resolveAudioStream("v1").getOrNull()?.stream)
        // Now available: a cached refusal would have stranded the user on a permanently dead track.
        assertEquals("https://cdn.test/ok.webm", source.resolveAudioStream("v1").getOrNull()?.stream?.url)
        assertEquals(2, instance.requestCount)
    }

    // ---- Health --------------------------------------------------------------

    @Test
    fun `a recently failed instance is skipped on the next resolve`() = runBlocking {
        val dead = newServer()
        val live = newServer()
        dead.enqueue(MockResponse().setResponseCode(403).setBody("Endpoint disabled"))
        live.enqueue(MockResponse().setBody(audioBody("https://cdn.test/1.webm?expire=9999999999")))

        val source = sourceOver(base(dead), base(live))
        source.resolveAudioStream("v1")
        assertEquals(1, dead.requestCount)

        // A different track, so the cache cannot hide the second call.
        live.enqueue(MockResponse().setBody(audioBody("https://cdn.test/2.webm?expire=9999999999")))
        val second = source.resolveAudioStream("v2")

        assertEquals("https://cdn.test/2.webm?expire=9999999999", second.getOrNull()?.stream?.url)
        // Still cooling down, so the dead host cost nothing this time.
        assertEquals(1, dead.requestCount)
        assertEquals(2, live.requestCount)
    }

    @Test
    fun `a cooling-down instance is still tried when nothing else is left`() = runBlocking {
        val only = newServer()
        only.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        only.enqueue(MockResponse().setBody(audioBody("https://cdn.test/recovered.webm")))

        val source = sourceOver(base(only))
        assertTrue(source.resolveAudioStream("v1").isFailure)

        // It has recovered. Benching it for the whole cooldown would leave the user with no audio
        // at all, which is worse than one extra round trip.
        val second = source.resolveAudioStream("v2")
        assertEquals("https://cdn.test/recovered.webm", second.getOrNull()?.stream?.url)
        assertEquals(2, only.requestCount)
    }

    @Test
    fun `a healthy answer clears a previous failure`() = runBlocking {
        val first = newServer()
        val second = newServer()
        // First is dead, second answers — then second answers again for a new track.
        first.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        second.enqueue(MockResponse().setBody(audioBody("https://cdn.test/a.webm?expire=9999999999")))
        second.enqueue(MockResponse().setBody(audioBody("https://cdn.test/b.webm?expire=9999999999")))

        val source = sourceOver(base(first), base(second))
        source.resolveAudioStream("v1")
        source.resolveAudioStream("v2")

        // The live host stayed live: no self-inflicted benching from a neighbour's failure.
        assertEquals(2, second.requestCount)
    }
}
