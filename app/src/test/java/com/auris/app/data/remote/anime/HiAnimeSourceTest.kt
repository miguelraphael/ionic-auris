package com.auris.app.data.remote.anime

import com.auris.app.source.SourceItem
import com.auris.app.source.SourceUnit
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class HiAnimeSourceTest {
    private val server = MockWebServer()
    private lateinit var source: HiAnimeSource
    private val entry = SourceItem("test-show-1335", "Test Show", null)
    @Before fun setup() {
        server.start()
        source = HiAnimeSource(OkHttpClient(), server.url("/"))
    }
    @After fun teardown() { server.shutdown() }
    private fun respond(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }
    private fun htmlResponse(html: String) = buildJsonObject {
        put("status", true); put("html", html)
    }.toString()

    @Test fun catalogueMapsCardsAndPaginationAndEncodesSearch() = runBlocking {
        respond("""<div class="film_list-wrap"><div class="flw-item"><img class="film-poster-img" data-src="/poster.jpg"><h3 class="film-name"><a href="/test-show-1335" title="Test &amp; Show">Test</a></h3></div></div><ul class="pagination"><li><a href="/search?page=3">Next</a></li></ul>""")
        val page = source.search(2, "A & B?", FilterList())
        assertEquals("Test & Show", page.items.single().title)
        assertEquals("test-show-1335", page.items.single().url)
        assertEquals(server.url("/poster.jpg").toString(), page.items.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
        val request = server.takeRequest()
        assertEquals("A & B?", request.requestUrl!!.queryParameter("keyword"))
        assertEquals("2", request.requestUrl!!.queryParameter("page"))
        assertNotNull(request.getHeader("Referer"))
    }

    @Test fun rejectsChallengePageInsteadOfReportingAnEmptyCatalogue() = runBlocking {
        respond("<html><title>Just a moment</title></html>")
        assertTrue(runCatching { source.popular(1) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun episodeIdentitiesSurviveFractionalNumbersAndDuplicateMarkup() = runBlocking {
        respond(htmlResponse("""<a class="ep-item" data-id="22676" data-number="1"><span class="ep-name">First</span></a><a class="ep-item" data-id="22677" data-number="1.5"><span class="ep-name">Special</span></a><a class="ep-item" data-id="22677" data-number="1.5"></a>"""))
        val units = source.units(entry)
        assertEquals(listOf("22676", "22677"), units.map { it.url })
        assertEquals(listOf(1, 2), units.map { it.index })
        assertEquals("Episode 1.5 · Special", units[1].title)
        assertEquals("/api/theme/episode/list/1335", server.takeRequest().path)
    }

    @Test fun resolvesStreamHeadersAndEnglishSubtitlesFromObservedEmbedProtocol() = runBlocking {
        val embed = server.url("/stream/mal/20/1/sub")
        val hash = Base64.getEncoder().encodeToString(embed.toString().toByteArray())
        respond(htmlResponse("""<div class="server-item" data-type="sub" data-server-name="ZokoAnime" data-hash="$hash"></div>"""))
        val config = buildJsonObject {
            put("src", server.url("/master.m3u8").toString())
            put("subtitles", buildJsonArray { add(buildJsonObject {
                put("src", server.url("/english.vtt").toString()); put("lang", "en")
                put("label", "English"); put("default", true)
            }) })
        }.toString().toByteArray()
        val key = "otaku-embed-v1".toByteArray()
        val blob = Base64.getEncoder().encodeToString(ByteArray(config.size) { (config[it].toInt() xor key[it % key.size].toInt()).toByte() })
        respond("""<script>window.__P="$blob";</script>""")
        val target = source.streams(entry, SourceUnit("22676", 1, "First")).single()
        assertEquals(server.url("/master.m3u8").toString(), target.url)
        assertEquals(server.url("/").toString(), target.headers["Referer"])
        assertEquals("application/x-mpegURL", target.mimeType)
        assertTrue(target.isAdaptive)
        assertEquals("ZokoAnime · Sub", target.label)
        assertEquals("en", target.subtitles.single().language)
        assertTrue(target.subtitles.single().isDefault)
        assertEquals("22676", server.takeRequest().requestUrl!!.queryParameter("episodeId"))
        assertEquals(embed.encodedPath, server.takeRequest().path)
    }

    @Test fun unsupportedServerIsAnExplicitError() = runBlocking {
        respond(htmlResponse("""<div class="server-item" data-type="sub" data-server-name="HD-1" data-hash="unused"></div>"""))
        val error = runCatching { source.streams(entry, SourceUnit("22676", 1, "First")) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("No supported server"))
    }

    @Test fun invalidEpisodeIsRejectedBeforeAnyNetworkRequest() = runBlocking {
        assertTrue(runCatching { source.streams(entry, SourceUnit("", 1, "First")) }.isFailure)
        assertEquals(0, server.requestCount)
    }
}
