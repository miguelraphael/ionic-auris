package com.auris.app.data.remote.music

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cover lookup behaviour, against a fake iTunes endpoint.
 *
 * The interesting failure is a *wrong* cover: the Search API always answers, so "found something"
 * is not the same as "found the right song". These tests pin the matching rules and the size
 * upgrade, not the JSON parsing alone.
 *
 * A fresh server and source per test: the source caches misses, so sharing one would let an
 * earlier test's cache entry hide a later one's request.
 */
class AlbumArtSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ITunesAlbumArtSource

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        source = ITunesAlbumArtSource(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    /** Each entry is `title|artist|artworkUrl`. */
    private fun enqueueResults(vararg songs: String) {
        val results = songs.joinToString(",") { song ->
            val (title, artist, art) = song.split("|")
            """{"trackName":"$title","artistName":"$artist","artworkUrl100":"$art"}"""
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/javascript; charset=utf-8")
                .setBody("""{"resultCount":${songs.size},"results":[$results]}"""),
        )
    }

    @Test
    fun `returns the 600px version of the matching cover`() = runBlocking {
        enqueueResults("ESCORT|Chase Atlantic|https://art.test/ESCORT/100x100bb.jpg")

        val artwork = source.artworkFor("ESCORT", "Chase Atlantic")

        assertEquals("https://art.test/ESCORT/600x600bb.jpg", artwork)
    }

    @Test
    fun `matches an official video title to the song title`() = runBlocking {
        enqueueResults("Flowers|Miley Cyrus|https://art.test/flowers/100x100bb.jpg")

        val artwork = source.artworkFor(
            title = "Miley Cyrus - Flowers (Official Video)",
            artist = "MileyCyrusVEVO",
        )

        assertEquals("https://art.test/flowers/600x600bb.jpg", artwork)
    }

    @Test
    fun `strips video annotations and channel suffixes from the query`() = runBlocking {
        enqueueResults("Kill Bill|SZA|https://art.test/sos/100x100bb.jpg")

        val artwork = source.artworkFor(
            title = "Kill Bill (Official Video)",
            artist = "SZAVEVO",
        )

        assertEquals("https://art.test/sos/600x600bb.jpg", artwork)
        val term = server.takeRequest().requestUrl!!.queryParameter("term").orEmpty()
        assertTrue("term was \"$term\"", term.contains("Kill Bill"))
        assertTrue("term was \"$term\"", !term.contains("Official"))
        assertTrue("term was \"$term\"", !term.contains("VEVO"))
    }

    @Test
    fun `rejects a same-titled song by a different artist`() = runBlocking {
        enqueueResults("Intro|Some Other Band|https://art.test/wrong/100x100bb.jpg")

        assertNull(source.artworkFor("Intro", "The xx"))
    }

    @Test
    fun `prefers the result whose artist agrees`() = runBlocking {
        enqueueResults(
            "Home|Another Artist|https://art.test/wrong/100x100bb.jpg",
            "Home|The Artist|https://art.test/right/100x100bb.jpg",
        )

        assertEquals(
            "https://art.test/right/600x600bb.jpg",
            source.artworkFor("Home", "The Artist"),
        )
    }

    @Test
    fun `missing artwork falls back to null`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"resultCount":0,"results":[]}"""))

        assertNull(source.artworkFor("ESCORT", "Chase Atlantic"))
    }
}
