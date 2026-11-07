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

class AllAnimeSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: AllAnimeSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val serverUrl = server.url("/api")
        val clockUrl = server.url("/apivtwo/clock.json")
        val siteUrl = server.url("/")
        source = AllAnimeSource(
            client = OkHttpClient(),
            apiGateway = serverUrl,
            site = siteUrl,
            clockGateway = clockUrl,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun popularParsesRecommendations() = runBlocking {
        val json = """
            {
              "data": {
                "queryPopular": {
                  "recommendations": [
                    {
                      "anyCard": {
                        "_id": "show_123",
                        "name": "Jujutsu Kaisen",
                        "thumbnail": "https://img.allanime.day/cover.jpg",
                        "airedStart": { "year": 2020 }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.popular(1)
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("show_123", item.url)
        assertEquals("Jujutsu Kaisen", item.title)
        assertEquals("https://img.allanime.day/cover.jpg", item.thumbnailUrl)
        assertEquals(2020, item.year)

        val recorded = server.takeRequest()
        assertTrue(recorded.requestUrl!!.queryParameter("variables")!!.contains("\"type\":\"anime\""))
    }

    @Test
    fun searchSendsQueryAndParsesEdges() = runBlocking {
        val json = """
            {
              "data": {
                "shows": {
                  "edges": [
                    {
                      "_id": "naruto_id",
                      "name": "Naruto Shippuden",
                      "thumbnail": "https://img.allanime.day/naruto.jpg",
                      "season": { "year": 2007 }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.search(1, "Naruto", FilterList())
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("naruto_id", item.url)
        assertEquals("Naruto Shippuden", item.title)
        assertEquals(2007, item.year)

        val recorded = server.takeRequest()
        assertTrue(recorded.requestUrl!!.queryParameter("variables")!!.contains("\"query\":\"Naruto\""))
    }

    @Test
    fun detailsEnrichesMetadata() = runBlocking {
        val json = """
            {
              "data": {
                "show": {
                  "_id": "naruto_id",
                  "thumbnail": "https://img.allanime.day/large.jpg",
                  "description": "A story about a ninja<br>who wants to become Hokage.",
                  "studios": ["Studio Pierrot"],
                  "availableEpisodes": { "sub": 500 },
                  "season": { "year": 2007 }
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("naruto_id", "Naruto", null)
        val detailed = source.details(entry)

        assertEquals("Studio Pierrot", detailed.author)
        assertEquals(500, detailed.episodeCount)
        assertEquals(2007, detailed.year)
        assertEquals("https://img.allanime.day/large.jpg", detailed.thumbnailUrl)
    }

    @Test
    fun unitsSortsEpisodesNumerically() = runBlocking {
        val json = """
            {
              "data": {
                "show": {
                  "_id": "show_1",
                  "availableEpisodesDetail": {
                    "sub": ["10", "2", "1", "3"]
                  }
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("show_1", "Test Show", null)
        val units = source.units(entry)

        assertEquals(4, units.size)
        assertEquals("Episode 1", units[0].title)
        assertEquals("Episode 2", units[1].title)
        assertEquals("Episode 3", units[2].title)
        assertEquals("Episode 10", units[3].title)
        assertEquals(1, units[0].index)
        assertEquals(10, units[3].index)
        assertEquals("show_1<&sep>1", units[0].url)
        assertEquals("show_1<&sep>10", units[3].url)
    }

    @Test
    fun streamsResolvesClockHlsAndDecryptsSources() = runBlocking {
        val streamsJson = """
            {
              "data": {
                "episode": {
                  "sourceUrls": [
                    {
                      "sourceUrl": "/apivtwo/clock?id=clock_test_id",
                      "sourceName": "Default",
                      "type": "iframe"
                    },
                    {
                      "sourceUrl": "https://mp4upload.com/embed-test.html",
                      "sourceName": "Mp4",
                      "type": "iframe"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val clockJson = """
            {
              "links": [
                {
                  "link": "https://cdn.example.com/hls/master.m3u8",
                  "hls": true,
                  "resolutionStr": "1080p"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(streamsJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(clockJson))

        val entry = SourceItem("show_1", "Show", null)
        val unit = SourceUnit("show_1<&sep>1", 1, "Episode 1")
        val targets = source.streams(entry, unit)

        assertEquals(2, targets.size)
        val hlsTarget = targets[0]
        assertEquals("https://cdn.example.com/hls/master.m3u8", hlsTarget.url)
        assertTrue(hlsTarget.isAdaptive)
        assertEquals("AllAnime · Default (HLS)", hlsTarget.label)

        val mp4Target = targets[1]
        assertEquals("https://mp4upload.com/embed-test.html", mp4Target.url)
        assertFalse(mp4Target.isAdaptive)
        assertEquals("AllAnime · Mp4", mp4Target.label)
    }
}
