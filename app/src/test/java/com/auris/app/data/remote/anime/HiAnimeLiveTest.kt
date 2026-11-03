package com.auris.app.data.remote.anime

import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in network test of the shipped Kotlin connector, including actual media/subtitle bytes. */
class HiAnimeLiveTest {
    @Test fun browseToRealVideoBytes() = runBlocking {
        assumeTrue("Set AURIS_LIVE_ANIME=1 to run the public-source smoke test", System.getenv("AURIS_LIVE_ANIME") == "1")
        val source = HiAnimeSource()
        assertTrue(source.popular(1).items.isNotEmpty())
        assertTrue(source.latest(1).items.isNotEmpty())
        val search = source.search(1, "Naruto", FilterList())
        val title = search.items.first()
        val episodes = source.units(title)
        assertTrue(episodes.isNotEmpty())
        val targets = source.streams(title, episodes.first())
        assertTrue(targets.isNotEmpty())
        val target = targets.first()
        val client = OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        fun read(url: String, range: Boolean = false): ByteArray {
            val request = Request.Builder().url(url)
            target.headers.forEach { (name, value) -> request.header(name, value) }
            if (range) request.header("Range", "bytes=0-65535")
            val start = System.nanoTime()
            return client.newCall(request.build()).execute().use { response ->
                assertTrue("Media request failed: ${response.code}", response.isSuccessful)
                val body = response.body!!
                val bytes = if (range) body.byteStream().use { it.readNBytes(65536) } else body.bytes()
                println("${if (range) "Video sample" else "Playlist/subtitle"}: ${bytes.size} bytes in ${(System.nanoTime()-start)/1_000_000} ms")
                bytes
            }
        }
        val master = read(target.url).toString(Charsets.UTF_8)
        assertTrue(master.startsWith("#EXTM3U"))
        val variant = target.url.toHttpUrl().resolve(master.lineSequence().first { it.isNotBlank() && !it.startsWith("#") })!!
        val media = read(variant.toString()).toString(Charsets.UTF_8)
        assertTrue(media.startsWith("#EXTM3U"))
        val segment = variant.resolve(media.lineSequence().first { it.isNotBlank() && !it.startsWith("#") })!!
        val bytes = read(segment.toString(), range = true)
        assertTrue(bytes.isNotEmpty())
        assertEquals(0x47, bytes[0].toInt() and 0xff)
        val english = target.subtitles.firstOrNull { it.language == "en" }
        assertNotNull("No English subtitle track", english)
        assertTrue(read(english!!.url).toString(Charsets.UTF_8).trimStart('\uFEFF').startsWith("WEBVTT"))
        println("PASS: ${title.title}; ${episodes.size} episodes; ${targets.size} available streams; real MPEG-TS and English VTT")
    }
}
