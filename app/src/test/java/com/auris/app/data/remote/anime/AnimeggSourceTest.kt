package com.auris.app.data.remote.anime

import com.auris.app.source.SourceItem
import com.auris.app.source.SourceUnit
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AnimeggSourceTest {
    @Test
    fun browsesPopularSeriesAndResolvesEpisodesAndStreams() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = AnimeggSource(OkHttpClient(), server.url("/"))

            // 1. Popular catalogue
            server.enqueue(
                MockResponse().setBody(
                    """
                    <div class="fea">
                      <div class="img"><img src="/cover.jpg"><a href="/series/one-piece" class="fealink">Watch Anime</a></div>
                      <div class="rightpop"><a href="/series/one-piece">One Piece</a></div>
                    </div>
                    <ul class="pagination">
                      <li><a href="?start=25">Next</a></li>
                    </ul>
                    """.trimIndent()
                )
            )
            val page = source.popular(1)
            assertEquals(1, page.items.size)
            assertEquals("One Piece", page.items.single().title)
            assertEquals("/series/one-piece", page.items.single().url)
            assertEquals(server.url("/cover.jpg").toString(), page.items.single().thumbnailUrl)
            assertTrue(page.hasNextPage)

            // 2. Episodes listing
            server.enqueue(
                MockResponse().setBody(
                    """
                    <div class="media-body"><h1>One Piece</h1></div>
                    <li><div><a href="/one-piece-episode-2" class="anm_det_pop">One Piece 2</a></div></li>
                    <li><div><a href="/one-piece-episode-1" class="anm_det_pop">One Piece 1</a></div></li>
                    """.trimIndent()
                )
            )
            val episodes = source.units(page.items.single())
            assertEquals(2, episodes.size)
            assertEquals(listOf("One Piece 1", "One Piece 2"), episodes.map { it.title })
            assertEquals(listOf("/one-piece-episode-1", "/one-piece-episode-2"), episodes.map { it.url })

            // 3. Episode page with embed iframe
            server.enqueue(
                MockResponse().setBody(
                    """
                    <div class="tab-pane">
                      <iframe class="video" src="/embed/12345" allowfullscreen></iframe>
                    </div>
                    """.trimIndent()
                )
            )

            // 4. Embed player page with videoSources
            server.enqueue(
                MockResponse().setBody(
                    """
                    <script>
                      var videoSources = [{file: "/play/123/video.mp4?for=abc", label: "720p"}, {file: "/play/456/video.mp4?for=abc", label: "1080p"}];
                    </script>
                    """.trimIndent()
                )
            )

            val streams = source.streams(page.items.single(), episodes.first())
            assertEquals(2, streams.size)
            val topStream = streams.first()
            assertEquals("Animegg · 1080p", topStream.label)
            assertEquals(server.url("/play/456/video.mp4?for=abc").toString(), topStream.url)
            assertEquals("video/mp4", topStream.mimeType)
            assertEquals(server.url("/").toString(), topStream.headers["Referer"])
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun searchParsesResultsCorrectly() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = AnimeggSource(OkHttpClient(), server.url("/"))
            server.enqueue(
                MockResponse().setBody(
                    """
                    <a href="/series/naruto" class="mse">
                      <div class="media searchre"><img src="/naruto.jpg">
                        <div class="media-body"><h2>Naruto</h2></div>
                      </div>
                    </a>
                    """.trimIndent()
                )
            )
            val result = source.search(1, "naruto", FilterList())
            assertEquals(1, result.items.size)
            assertEquals("Naruto", result.items.single().title)
            assertEquals("/series/naruto", result.items.single().url)
            assertEquals(server.url("/naruto.jpg").toString(), result.items.single().thumbnailUrl)
            assertFalse(result.hasNextPage)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun emptyEpisodeListThrowsIOException() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = AnimeggSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("<div class='container'><p>No episodes here</p></div>"))
            val item = SourceItem("/series/empty", "Empty", null)
            val result = runCatching { source.units(item) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IOException)
        } finally {
            server.shutdown()
        }
    }
}
