package com.auris.app.data.remote.anime

import com.auris.app.source.SourceItem
import com.auris.app.source.SourceUnit
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AnimepaheSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: AnimepaheSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = AnimepaheSource(
            client = OkHttpClient(),
            site = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun popularAndAiringParsesCorrectly() = runBlocking {
        val json = """
            {
              "total": 30,
              "per_page": 30,
              "current_page": 1,
              "last_page": 2,
              "data": [
                {
                  "id": 101,
                  "anime_title": "Solo Leveling Season 2",
                  "anime_session": "session_solo",
                  "snapshot": "https://i.animepahe.ru/snapshots/solo.jpg",
                  "episode": 1
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.popular(1)
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("session_solo", item.url)
        assertEquals("Solo Leveling Season 2", item.title)
        assertEquals("https://i.animepahe.ru/snapshots/solo.jpg", item.thumbnailUrl)
        assertTrue(page.hasNextPage)

        val recorded = server.takeRequest()
        assertEquals("/api?m=airing&page=1", recorded.path)
    }

    @Test
    fun searchParsesSearchResults() = runBlocking {
        val json = """
            {
              "total": 1,
              "data": [
                {
                  "id": 99,
                  "title": "Bleach: Thousand-Year Blood War",
                  "session": "session_bleach",
                  "poster": "https://i.animepahe.ru/posters/bleach.jpg",
                  "episodes": 13,
                  "year": 2022
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.search(1, "Bleach", FilterList())
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("session_bleach", item.url)
        assertEquals("Bleach: Thousand-Year Blood War", item.title)
        assertEquals(13, item.episodeCount)
        assertEquals(2022, item.year)

        val recorded = server.takeRequest()
        assertEquals("/api?m=search&q=Bleach", recorded.path)
    }

    @Test
    fun unitsParsesReleases() = runBlocking {
        val json = """
            {
              "total": 2,
              "current_page": 1,
              "last_page": 1,
              "data": [
                {
                  "id": 1,
                  "episode": 1.0,
                  "session": "ep1_session",
                  "snapshot": "https://i.animepahe.ru/snap1.jpg"
                },
                {
                  "id": 2,
                  "episode": 2.0,
                  "session": "ep2_session",
                  "snapshot": "https://i.animepahe.ru/snap2.jpg"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("anime_session_123", "Anime Title", null)
        val units = source.units(entry)

        assertEquals(2, units.size)
        assertEquals(1, units[0].index)
        assertEquals("Episode 1", units[0].title)
        assertEquals("anime_session_123/ep1_session", units[0].url)
        assertEquals("https://i.animepahe.ru/snap1.jpg", units[0].thumbnailUrl)

        assertEquals(2, units[1].index)
        assertEquals("Episode 2", units[1].title)
        assertEquals("anime_session_123/ep2_session", units[1].url)
    }

    @Test
    fun streamsResolvesKwikDirectHlsStream() = runBlocking {
        val kwikEmbedUrl = server.url("/e/kwik_720").toString()
        val json = """
            {
              "data": [
                {
                  "720p": {
                    "kwik": "$kwikEmbedUrl",
                    "audio": "jpn"
                  }
                }
              ]
            }
        """.trimIndent()

        val kwikHtml = """
            <html>
            <body>
            <script type="text/javascript">
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('const 0=\'1\';',2,2,'source|https://kwik.example.com/master720.m3u8'.split('|'),0,{}))
            </script>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
        server.enqueue(MockResponse().setResponseCode(200).setBody(kwikHtml))

        val entry = SourceItem("anime_123", "Anime", null)
        val unit = SourceUnit("anime_123/ep_456", 1, "Episode 1")
        val targets = source.streams(entry, unit)

        assertEquals(1, targets.size)
        assertEquals("https://kwik.example.com/master720.m3u8", targets[0].url)
        assertTrue(targets[0].isAdaptive)
        assertEquals("Animepahe · 720p (jpn)", targets[0].label)
        assertEquals("https://kwik.cx/", targets[0].headers["Referer"])
    }

    @Test
    fun streamsResolvesKwikFormPostToken() = runBlocking {
        val kwikEmbedUrl = server.url("/e/kwik_post").toString()
        val json = """
            {
              "data": [
                {
                  "1080p": {
                    "kwik": "$kwikEmbedUrl",
                    "audio": "eng"
                  }
                }
              ]
            }
        """.trimIndent()

        val postActionPath = "/d/download_1080"
        val kwikHtml = """
            <html>
            <body>
            <form action="$postActionPath" method="POST">
                <input type="hidden" name="_token" value="secret_csrf_token_abc" />
                <button type="submit">Download</button>
            </form>
            </body>
            </html>
        """.trimIndent()

        // 1. API links response
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
        // 2. Kwik embed HTML response containing form
        server.enqueue(MockResponse().setResponseCode(200).setBody(kwikHtml))
        // 3. POST action response with 302 Found and Location header
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://kwik.example.com/stream1080.m3u8")
        )

        val entry = SourceItem("anime_123", "Anime", null)
        val unit = SourceUnit("anime_123/ep_456", 1, "Episode 1")
        val targets = source.streams(entry, unit)

        assertEquals(1, targets.size)
        assertEquals("https://kwik.example.com/stream1080.m3u8", targets[0].url)
        assertTrue(targets[0].isAdaptive)
        assertEquals("Animepahe · 1080p (eng)", targets[0].label)
        assertEquals("https://kwik.cx/", targets[0].headers["Referer"])
    }

    @Test
    fun streamsResolvesDirectMp4StreamAsNonAdaptive() = runBlocking {
        val kwikEmbedUrl = server.url("/e/kwik_mp4").toString()
        val json = """
            {
              "data": [
                {
                  "360p": {
                    "kwik": "$kwikEmbedUrl",
                    "audio": "jpn"
                  }
                }
              ]
            }
        """.trimIndent()

        val kwikHtml = """
            <html>
            <body>
            <script>
            const source = "https://kwik.example.com/direct_video.mp4";
            </script>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
        server.enqueue(MockResponse().setResponseCode(200).setBody(kwikHtml))

        val entry = SourceItem("anime_123", "Anime", null)
        val unit = SourceUnit("anime_123/ep_456", 1, "Episode 1")
        val targets = source.streams(entry, unit)

        assertEquals(1, targets.size)
        assertEquals("https://kwik.example.com/direct_video.mp4", targets[0].url)
        assertFalse(targets[0].isAdaptive)
        assertEquals("Animepahe · 360p (jpn)", targets[0].label)
    }

    @Test
    fun streamsHandlesPartialLinkFailuresGracefully() = runBlocking {
        val kwikFailUrl = server.url("/e/fail").toString()
        val kwikSuccessUrl = server.url("/e/success").toString()

        val json = """
            {
              "data": [
                {
                  "720p": {
                    "kwik": "$kwikFailUrl",
                    "audio": "jpn"
                  },
                  "1080p": {
                    "kwik": "$kwikSuccessUrl",
                    "audio": "jpn"
                  }
                }
              ]
            }
        """.trimIndent()

        val successHtml = """
            <html>
            <script>
            const source = 'https://kwik.example.com/master1080.m3u8';
            </script>
            </html>
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))
        // One link returns HTTP 500 error, other returns 200 with valid source
        server.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(successHtml))

        val entry = SourceItem("anime_123", "Anime", null)
        val unit = SourceUnit("anime_123/ep_456", 1, "Episode 1")
        val targets = source.streams(entry, unit)

        assertEquals(1, targets.size)
        assertEquals("https://kwik.example.com/master1080.m3u8", targets[0].url)
        assertEquals("Animepahe · 1080p (jpn)", targets[0].label)
    }

    @Test
    fun mirrorDomainsIncludeStandardMirrors() {
        assertTrue(AnimepaheSource.MIRROR_DOMAINS.contains("animepahe.ru"))
        assertTrue(AnimepaheSource.MIRROR_DOMAINS.contains("animepahe.org"))
        assertTrue(AnimepaheSource.MIRROR_DOMAINS.contains("animepahe.com"))
        assertTrue(AnimepaheSource.MIRROR_DOMAINS.contains("animepahe.pw"))
        assertTrue(AnimepaheSource.MIRROR_DOMAINS.contains("animepahe.su"))
    }
}
