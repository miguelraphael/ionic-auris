package com.auris.app.data.remote.anime

import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class AnimeKhorSourceTest {
    @Test fun browsesSeriesAndResolvesSourceProvidedEnglishEmbed() = runBlocking {
        val site = MockWebServer(); val video = MockWebServer()
        site.start(); video.start()
        try {
            val source = AnimeKhorSource(OkHttpClient(), site.url("/"), video.url("/"))
            site.enqueue(MockResponse().setBody("""<div class=listupd><div class=bsx><a href='/anime/test/' title='Test show'><img src='/cover.jpg'></a></div></div><div class=hpage><a href='?page=2&order=popular'>Next</a></div>"""))
            val page = source.popular(1)
            assertEquals("Test show", page.items.single().title)
            assertTrue(page.hasNextPage)
            assertEquals("popular", site.takeRequest().requestUrl!!.queryParameter("order"))
            site.enqueue(MockResponse().setBody("""<div class=eplister><li><a href='/episode-2'><div class=epl-title>Episode 2</div></a></li><li><a href='/episode-1'><div class=epl-title>Episode 1</div></a></li></div>"""))
            val episodes = source.units(page.items.single())
            assertEquals(listOf("Episode 1", "Episode 2"), episodes.map { it.title })
            val embed = Base64.getEncoder().encodeToString("<iframe src='${video.url("/videoembed/123")}'></iframe>".toByteArray())
            site.enqueue(MockResponse().setBody("<select class=mirror><option value='$embed'>OK.ru [ENGLISH]</option></select>"))
            val options = buildJsonObject { putJsonObject("flashvars") { putJsonObject("metadata") {
                putJsonArray("videos") { addJsonObject { put("name", "hd"); put("url", site.url("/video.mp4").toString()) } }
            } } }.toString().replace("&", "&amp;").replace("\"", "&quot;")
            video.enqueue(MockResponse().setBody("<div data-options=\"$options\"></div>"))
            val stream = source.streams(page.items.single(), episodes.first()).single()
            assertEquals(site.url("/video.mp4").toString(), stream.url)
            assertEquals("video/mp4", stream.mimeType)
            assertEquals(video.url("/").toString(), stream.headers["Referer"])
        } finally { site.shutdown(); video.shutdown() }
    }

    @Test fun unsupportedHostsAreReportedWithoutRequestingThem() = runBlocking {
        val site = MockWebServer(); site.start()
        try {
            site.enqueue(MockResponse().setBody("<select class=mirror><option>Unavailable</option></select>"))
            val result = runCatching { AnimeKhorSource(OkHttpClient(), site.url("/")).streams(
                SourceItem("/anime/test/", "Test", null), SourceUnit("/episode-1", 1, "Episode 1")) }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("no supported"))
        } finally { site.shutdown() }
    }
}
