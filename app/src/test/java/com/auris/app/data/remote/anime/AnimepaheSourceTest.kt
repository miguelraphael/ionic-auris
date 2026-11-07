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
    fun streamsExtractsKwikLinks() = runBlocking {
        val json = """
            {
              "data": [
                {
                  "720p": {
                    "kwik": "https://kwik.cx/e/kwik_720",
                    "audio": "jpn"
                  },
                  "1080p": {
                    "kwik": "https://kwik.cx/e/kwik_1080",
                    "audio": "jpn"
                  }
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("anime_123", "Anime", null)
        val unit = SourceUnit("anime_123/ep_456", 1, "Episode 1")
        val targets = source.streams(entry, unit)

        assertEquals(2, targets.size)
        assertEquals("https://kwik.cx/e/kwik_720", targets[0].url)
        assertEquals("Animepahe · 720p (jpn)", targets[0].label)
        assertEquals("https://kwik.cx/e/kwik_1080", targets[1].url)
        assertEquals("Animepahe · 1080p (jpn)", targets[1].label)
    }
}
