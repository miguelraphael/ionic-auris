package com.auris.app.data.remote.manga

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

class AllMangaSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var source: AllMangaSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val gatewayUrl = server.url("/api")
        val siteUrl = server.url("/")
        source = AllMangaSource(
            client = OkHttpClient(),
            apiGateway = gatewayUrl,
            site = siteUrl,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun popularParsesEdgesAndFormatsRelativeThumbnail() = runBlocking {
        val json = """
            {
              "data": {
                "mangas": {
                  "edges": [
                    {
                      "_id": "manga_one_piece",
                      "name": "One Piece",
                      "thumbnail": "mcovers/one_piece.jpg",
                      "airedStart": { "year": 1997 }
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
        assertEquals("manga_one_piece", item.url)
        assertEquals("One Piece", item.title)
        assertEquals("https://wp.youtube-anime.com/aln.youtube-anime.com/mcovers/one_piece.jpg", item.thumbnailUrl)
        assertEquals(1997, item.year)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("android_c-253", recorded.getHeader("app-version"))
        assertEquals("animechicken", recorded.getHeader("from-app"))
        assertEquals("android_c", recorded.getHeader("platformstr"))
        assertEquals("https://allmanga.to/", recorded.getHeader("Referer"))
        assertTrue(recorded.requestUrl!!.queryParameter("variables")!!.contains("\"query\":\"\""))
        assertTrue(recorded.requestUrl!!.queryParameter("extensions")!!.contains("2d48e19fb67ddcac42fbb885204b6abb0a84f406f15ef83f36de4a66f49f651a"))
    }

    @Test
    fun latestQueriesMangaList() = runBlocking {
        val json = """
            {
              "data": {
                "mangas": {
                  "edges": [
                    {
                      "_id": "manga_bleach",
                      "name": "Bleach",
                      "thumbnail": "https://img.example.com/bleach.jpg",
                      "season": { "year": 2001 }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.latest(1)
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("manga_bleach", item.url)
        assertEquals("Bleach", item.title)
        assertEquals("https://img.example.com/bleach.jpg", item.thumbnailUrl)
        assertEquals(2001, item.year)
    }

    @Test
    fun searchSendsTrimmedQuery() = runBlocking {
        val json = """
            {
              "data": {
                "mangas": {
                  "edges": [
                    {
                      "_id": "manga_naruto",
                      "name": "Naruto",
                      "thumbnail": "/mcovers/naruto.jpg",
                      "year": 1999
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.search(1, "  Naruto  ", FilterList())
        assertEquals(1, page.items.size)
        val item = page.items.first()
        assertEquals("manga_naruto", item.url)
        assertEquals("Naruto", item.title)
        assertEquals("https://wp.youtube-anime.com/aln.youtube-anime.com/mcovers/naruto.jpg", item.thumbnailUrl)
        assertEquals(1999, item.year)

        val recorded = server.takeRequest()
        assertTrue(recorded.requestUrl!!.queryParameter("variables")!!.contains("\"query\":\"Naruto\""))
    }

    @Test
    fun detailsEnrichesMetadataViaPost() = runBlocking {
        val json = """
            {
              "data": {
                "manga": {
                  "_id": "manga_123",
                  "name": "Berserk",
                  "thumbnail": "mcovers/berserk.jpg",
                  "description": "Guts is a wanderer<br>fighting against his fate.",
                  "authors": ["Kentarou Miura"],
                  "status": "Releasing",
                  "availableChaptersDetail": {
                    "sub": ["1", "2", "3"]
                  }
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_123", "Berserk", null)
        val detailed = source.details(entry)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("android_c-253", recorded.getHeader("app-version"))
        assertEquals("https://allmanga.to/", recorded.getHeader("Referer"))
        val bodyString = recorded.body.readUtf8()
        assertTrue(bodyString.contains("query(\$_id: String!)"))
        assertTrue(bodyString.contains("\"_id\":\"manga_123\""))

        assertEquals("Berserk", detailed.title)
        assertEquals("Kentarou Miura", detailed.author)
        assertEquals(3, detailed.episodeCount)
        assertEquals("https://wp.youtube-anime.com/aln.youtube-anime.com/mcovers/berserk.jpg", detailed.thumbnailUrl)
    }

    @Test
    fun unitsSortsChaptersNumerically() = runBlocking {
        val json = """
            {
              "data": {
                "manga": {
                  "_id": "manga_123",
                  "name": "Chainsaw Man",
                  "availableChaptersDetail": {
                    "sub": ["10", "2", "1", "3"]
                  }
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_123", "Chainsaw Man", null)
        val units = source.units(entry)

        assertEquals(4, units.size)
        assertEquals("Chapter 1", units[0].title)
        assertEquals("Chapter 2", units[1].title)
        assertEquals("Chapter 3", units[2].title)
        assertEquals("Chapter 10", units[3].title)
        assertEquals(1, units[0].index)
        assertEquals(2, units[1].index)
        assertEquals(3, units[2].index)
        assertEquals(10, units[3].index)
        assertEquals("manga_123<&sep>1", units[0].url)
        assertEquals("manga_123<&sep>10", units[3].url)
    }

    @Test
    fun unitsFallsBackToRawWhenSubIsEmpty() = runBlocking {
        val json = """
            {
              "data": {
                "manga": {
                  "_id": "manga_raw",
                  "name": "Raw Manga",
                  "availableChaptersDetail": {
                    "sub": [],
                    "raw": ["1", "2"]
                  }
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_raw", "Raw Manga", null)
        val units = source.units(entry)

        assertEquals(2, units.size)
        assertEquals("Chapter 1", units[0].title)
        assertEquals("Chapter 2", units[1].title)
    }

    @Test
    fun pagesParsesPictureUrlsWithRequiredHeaders() = runBlocking {
        val json = """
            {
              "data": {
                "chapterPages": {
                  "edges": [
                    {
                      "pictureUrls": [
                        {
                          "num": 1,
                          "url": "mcovers/ch1/p1.jpg"
                        },
                        {
                          "num": 2,
                          "url": "https://ytimgf.youtube-anime.com/ch1/p2.jpg"
                        }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_123", "One Piece", null)
        val unit = SourceUnit("manga_123<&sep>1", 1, "Chapter 1")
        val pages = source.pages(entry, unit)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("https://allmanga.to/", recorded.getHeader("Referer"))
        val variables = recorded.requestUrl!!.queryParameter("variables")!!
        assertTrue(variables.contains("\"mangaId\":\"manga_123\""))
        assertTrue(variables.contains("\"chapterString\":\"1\""))
        assertTrue(variables.contains("\"translationType\":\"sub\""))
        assertTrue(variables.contains("\"limit\":250"))
        val extensions = recorded.requestUrl!!.queryParameter("extensions")!!
        assertTrue(extensions.contains("a062f1b131dae3d17c1950fad14640d066b988ac10347ed49cfbe70f5e7f661b"))

        assertEquals(2, pages.size)
        val page1 = pages[0]
        assertEquals(1, page1.index)
        assertEquals("https://wp.youtube-anime.com/aln.youtube-anime.com/mcovers/ch1/p1.jpg", page1.url)
        assertEquals("https://wp.youtube-anime.com/aln.youtube-anime.com/mcovers/ch1/p1.jpg", page1.imageUrl)
        assertEquals("https://allmanga.to/", page1.headers["Referer"])
        assertNotNull(page1.headers["User-Agent"])

        val page2 = pages[1]
        assertEquals(2, page2.index)
        assertEquals("https://ytimgf.youtube-anime.com/ch1/p2.jpg", page2.url)
        assertEquals("https://ytimgf.youtube-anime.com/ch1/p2.jpg", page2.imageUrl)
    }

    @Test
    fun searchHandlesNullAiredStartAndSeason() = runBlocking {
        val json = """
            {
              "data": {
                "mangas": {
                  "edges": [
                    {
                      "_id": "null_year_id",
                      "name": "Unknown Year Manga",
                      "thumbnail": "mcovers/unknown.jpg",
                      "airedStart": null,
                      "season": null
                    },
                    {
                      "_id": "normal_manga_id",
                      "name": "Normal Manga",
                      "thumbnail": "mcovers/normal.jpg",
                      "airedStart": { "year": 2010 }
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val page = source.search(1, "manga", FilterList())
        assertEquals(2, page.items.size)
        val nullYear = page.items.first { it.url == "null_year_id" }
        assertEquals("Unknown Year Manga", nullYear.title)
        assertNull(nullYear.year)
        val normal = page.items.first { it.url == "normal_manga_id" }
        assertEquals(2010, normal.year)
    }

    @Test
    fun detailsHandlesNullChaptersDetail() = runBlocking {
        val json = """
            {
              "data": {
                "manga": {
                  "_id": "manga_123",
                  "name": "Berserk",
                  "thumbnail": "mcovers/berserk.jpg",
                  "description": "Guts is a wanderer fighting against his fate.",
                  "authors": ["Kentarou Miura"],
                  "status": "Releasing",
                  "availableChaptersDetail": null
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_123", "Berserk", null, episodeCount = 7)
        val detailed = source.details(entry)

        assertEquals("Berserk", detailed.title)
        assertEquals("Kentarou Miura", detailed.author)
        assertEquals(7, detailed.episodeCount)
    }

    @Test
    fun unitsHandlesNullSubFallsBackToRaw() = runBlocking {
        val json = """
            {
              "data": {
                "manga": {
                  "_id": "manga_123",
                  "name": "Chainsaw Man",
                  "availableChaptersDetail": {
                    "sub": null,
                    "raw": ["2", "1"]
                  }
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_123", "Chainsaw Man", null)
        val units = source.units(entry)

        assertEquals(2, units.size)
        assertEquals("Chapter 1", units[0].title)
        assertEquals("Chapter 2", units[1].title)
    }

    @Test
    fun pagesSkipsNullPictureUrlElements() = runBlocking {
        val json = """
            {
              "data": {
                "chapterPages": {
                  "edges": [
                    {
                      "pictureUrls": [
                        null,
                        {
                          "num": 1,
                          "url": "mcovers/ch1/p1.jpg"
                        }
                      ]
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val entry = SourceItem("manga_123", "One Piece", null)
        val unit = SourceUnit("manga_123<&sep>1", 1, "Chapter 1")
        val pages = source.pages(entry, unit)

        assertEquals(1, pages.size)
        assertEquals(1, pages[0].index)
        assertEquals("https://wp.youtube-anime.com/aln.youtube-anime.com/mcovers/ch1/p1.jpg", pages[0].url)
    }
}
