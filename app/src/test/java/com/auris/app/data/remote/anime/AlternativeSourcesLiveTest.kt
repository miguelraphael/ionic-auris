package com.auris.app.data.remote.anime

import com.auris.app.data.remote.manga.MangaPillSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in checks of production connectors, including real image/video payloads. */
class AlternativeSourcesLiveTest {
    private val http = OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private fun enabled() = assumeTrue("Set AURIS_LIVE_SOURCES=1", System.getenv("AURIS_LIVE_SOURCES") == "1")

    @Test fun mangaPillBrowseToImageBytes() = runBlocking {
        enabled()
        val source = MangaPillSource(http)
        assertTrue(source.popular(1).items.isNotEmpty())
        assertTrue(source.latest(1).items.isNotEmpty())
        val entry = source.search(1, "Naruto", FilterList()).items.first { it.title == "Naruto" }
        val units = source.units(entry)
        assertTrue(units.isNotEmpty())
        val page = source.pages(entry, units.first()).first()
        val request = Request.Builder().url(page.imageUrl!!).header("Range", "bytes=0-1023")
        page.headers.forEach { (name, value) -> request.header(name, value) }
        http.newCall(request.build()).execute().use {
            assertTrue("Image request failed: ${it.code}", it.isSuccessful)
            assertTrue(it.header("Content-Type").orEmpty().startsWith("image/"))
            assertEquals(1024, it.body!!.byteStream().use { stream -> stream.readNBytes(1024) }.size)
        }
        println("PASS MangaPill: trending, latest, search, ${units.size} chapters, image resolution and bytes")
    }

    @Test fun animeggBrowseToVideoBytes() = runBlocking {
        enabled()
        val source = AnimeggSource(http)
        assertTrue(source.popular(1).items.isNotEmpty())
        assertTrue(source.latest(1).items.isNotEmpty())
        val entry = source.search(1, "Naruto", FilterList()).items.first { it.title == "Naruto" }
        val episodes = source.units(entry)
        assertTrue(episodes.isNotEmpty())
        val target = source.streams(entry, episodes.last()).first()
        val request = Request.Builder().url(target.url).header("Range", "bytes=0-1023")
        target.headers.forEach { (name, value) -> request.header(name, value) }
        http.newCall(request.build()).execute().use {
            assertTrue("Video request failed: ${it.code}", it.isSuccessful)
            val bytes = it.body!!.byteStream().use { stream -> stream.readNBytes(1024) }
            assertTrue("Expected MP4 header", bytes.take(32).toByteArray().toString(Charsets.ISO_8859_1).contains("ftyp"))
        }
        println("PASS Animegg: popular, latest, search, ${episodes.size} episodes, stream resolution and real MP4 bytes")
    }
}
