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
import java.io.IOException

class AsuraScansSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: AsuraScansSource

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        source = AsuraScansSource(
            client = OkHttpClient(),
            site = server.url("/"),
            apiSite = server.url("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test
    fun popularAndLatestFetchSeriesAndHandlePagination() {
        runBlocking {
            val jsonWithMore = """
                {
                    "data": [
                        {
                            "title": "Cyberpunk Chivalry",
                            "slug": "cyberpunk-chivalry-ab39de10",
                            "public_url": "/comics/cyberpunk-chivalry-ab39de10",
                            "cover": "https://cdn.asurascans.com/covers/cyberpunk.jpg",
                            "author": "Author One"
                        }
                    ],
                    "meta": {
                        "has_more": true
                    }
                }
            """.trimIndent()

            respond(jsonWithMore)
            val popular = source.popular(1)
            val popRequest = server.takeRequest()
            assertEquals("/api/series", popRequest.requestUrl!!.encodedPath)
            assertEquals("1", popRequest.requestUrl!!.queryParameter("page"))
            assertEquals("20", popRequest.requestUrl!!.queryParameter("limit"))
            assertEquals(1, popular.items.size)
            assertEquals("Cyberpunk Chivalry", popular.items[0].title)
            assertEquals("/comics/cyberpunk-chivalry-ab39de10", popular.items[0].url)
            assertEquals("https://cdn.asurascans.com/covers/cyberpunk.jpg", popular.items[0].thumbnailUrl)
            assertEquals("Author One", popular.items[0].author)
            assertTrue(popular.hasNextPage)

            val jsonNoMore = """
                {
                    "data": [
                        {
                            "title": "Solo Necromancer",
                            "slug": "solo-necromancer-1234",
                            "cover": "https://cdn.asurascans.com/covers/solo.jpg"
                        }
                    ],
                    "meta": {
                        "has_more": false
                    }
                }
            """.trimIndent()

            respond(jsonNoMore)
            val latest = source.latest(2)
            val latestRequest = server.takeRequest()
            assertEquals("2", latestRequest.requestUrl!!.queryParameter("page"))
            assertEquals(1, latest.items.size)
            assertEquals("/comics/solo-necromancer-1234", latest.items[0].url)
            assertFalse(latest.hasNextPage)

            // Fallback: items.size >= 20 when meta has_more is missing
            val itemsJson = (1..20).joinToString(",") { i ->
                """{"title":"Series $i","slug":"series-$i"}"""
            }
            respond("""{"data":[$itemsJson]}""")
            val pageFull = source.popular(3)
            server.takeRequest()
            assertEquals(20, pageFull.items.size)
            assertTrue(pageFull.hasNextPage)

            // Invalid page <= 0
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { source.popular(0) }
            }
        }
    }

    @Test
    fun searchEncodesQueryAndReturnsItems() {
        runBlocking {
            val searchJson = """
                {
                    "data": [
                        {
                            "title": "Return of the Blossoming Blade",
                            "slug": "return-of-the-blossoming-blade",
                            "public_url": "/comics/return-of-the-blossoming-blade",
                            "cover": "https://cdn.asurascans.com/covers/blade.jpg"
                        }
                    ]
                }
            """.trimIndent()

            respond(searchJson)
            val result = source.search(1, "Return & Blossom?", FilterList())
            val req = server.takeRequest()
            assertEquals("/api/search", req.requestUrl!!.encodedPath)
            assertEquals("Return & Blossom?", req.requestUrl!!.queryParameter("q"))
            assertEquals(1, result.items.size)
            assertEquals("Return of the Blossoming Blade", result.items[0].title)
            assertFalse(result.hasNextPage)

            // Page > 1 returns empty list without making an extra network request
            val pageTwo = source.search(2, "Return", FilterList())
            assertTrue(pageTwo.items.isEmpty())
            assertFalse(pageTwo.hasNextPage)
        }
    }

    @Test
    fun detailsExtractsSlugAndMapsMetadata() {
        runBlocking {
            val detailsJson = """
                {
                    "data": {
                        "title": "The Greatest Estate Developer",
                        "cover": "https://cdn.asurascans.com/covers/estate.jpg",
                        "author": "Kim Hyunsoo",
                        "total_chapters": 150,
                        "year": 2021
                    }
                }
            """.trimIndent()

            respond(detailsJson)
            val entry = SourceItem(
                url = "/comics/the-greatest-estate-developer-abc1234",
                title = "Estate Developer",
                thumbnailUrl = null,
            )
            val updated = source.details(entry)
            val req = server.takeRequest()
            assertEquals("/api/series/the-greatest-estate-developer-abc1234", req.requestUrl!!.encodedPath)
            assertEquals("The Greatest Estate Developer", updated.title)
            assertEquals("https://cdn.asurascans.com/covers/estate.jpg", updated.thumbnailUrl)
            assertEquals("Kim Hyunsoo", updated.author)
            assertEquals(150, updated.episodeCount)
            assertEquals(2021, updated.year)
        }
    }

    @Test
    fun unitsSortsInAscendingReadingOrderAndConstructsUrls() {
        runBlocking {
            // Chapters returned in descending order from API
            val chaptersJson = """
                {
                    "data": [
                        {
                            "id": "chap-3",
                            "number": 3,
                            "title": "Chapter 3 - The Battle",
                            "slug": "chapter-3",
                            "series_slug": "cyberpunk-chivalry"
                        },
                        {
                            "id": "chap-1",
                            "number": 1,
                            "title": "",
                            "slug": "chapter-1",
                            "series_slug": "cyberpunk-chivalry"
                        },
                        {
                            "id": "chap-2",
                            "number": 2,
                            "title": "   ",
                            "slug": "chapter-2",
                            "series_slug": "cyberpunk-chivalry"
                        }
                    ]
                }
            """.trimIndent()

            respond(chaptersJson)
            val entry = SourceItem(
                url = "/comics/cyberpunk-chivalry",
                title = "Cyberpunk Chivalry",
                thumbnailUrl = null,
            )
            val units = source.units(entry)
            val req = server.takeRequest()
            assertEquals("/api/series/cyberpunk-chivalry/chapters", req.requestUrl!!.encodedPath)

            assertEquals(3, units.size)
            // Ascending order: Chapter 1 first
            assertEquals(1, units[0].index)
            assertEquals("Chapter 1", units[0].title)
            assertEquals("/comics/cyberpunk-chivalry/chapter/1", units[0].url)

            assertEquals(2, units[1].index)
            assertEquals("Chapter 2", units[1].title)
            assertEquals("/comics/cyberpunk-chivalry/chapter/2", units[1].url)

            assertEquals(3, units[2].index)
            assertEquals("Chapter 3 - The Battle", units[2].title)
            assertEquals("/comics/cyberpunk-chivalry/chapter/3", units[2].url)
        }
    }

    @Test
    fun pagesExtractsImagesWithHeadersAndSortsByPageIndex() {
        runBlocking {
            val html = """
                <html>
                <body>
                    <div class="reading-content">
                        <img data-page-index="1" src="/asura-images/chapters/ch1/02.jpg">
                        <img data-page-index="0" src="https://cdn.asurascans.com/asura-images/chapters/ch1/01.jpg">
                        <img src="/asura-images/chapters/ch1/03.jpg">
                    </div>
                </body>
                </html>
            """.trimIndent()

            respond(html)
            val entry = SourceItem(url = "/comics/cyberpunk-chivalry", title = "Cyberpunk", thumbnailUrl = null)
            val unit = SourceUnit(url = "/comics/cyberpunk-chivalry/chapter/1", index = 1, title = "Chapter 1")

            val pages = source.pages(entry, unit)
            val req = server.takeRequest()
            assertEquals("/comics/cyberpunk-chivalry/chapter/1", req.requestUrl!!.encodedPath)
            assertEquals(server.url("/").toString(), req.getHeader("Referer"))
            assertTrue(req.getHeader("User-Agent")!!.contains("Mozilla/5.0"))

            assertEquals(3, pages.size)
            // Check sorted by data-page-index: 01.jpg (index 0) comes before 02.jpg (index 1)
            assertEquals(0, pages[0].index)
            assertEquals("https://cdn.asurascans.com/asura-images/chapters/ch1/01.jpg", pages[0].imageUrl)
            assertEquals(1, pages[1].index)
            assertEquals(server.url("/asura-images/chapters/ch1/02.jpg").toString(), pages[1].imageUrl)
            assertEquals(2, pages[2].index)
            assertEquals(server.url("/asura-images/chapters/ch1/03.jpg").toString(), pages[2].imageUrl)

            // Referer and User-Agent headers on pages
            assertEquals(server.url("/").toString(), pages[0].headers["Referer"])
            assertTrue(pages[0].headers["User-Agent"]!!.contains("Mozilla/5.0"))
        }
    }

    @Test
    fun pagesRejectsEmptyImagesWithIOException() {
        runBlocking {
            respond("<html><body><div>No images here</div></body></html>")
            val entry = SourceItem(url = "/comics/test", title = "Test", thumbnailUrl = null)
            val unit = SourceUnit(url = "/comics/test/chapter/1", index = 1, title = "Chapter 1")

            val error = runCatching { source.pages(entry, unit) }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains("readable chapter images"))
        }
    }

    @Test
    fun handlesHttpErrorsGracefully() {
        runBlocking {
            respond("{}", 429)
            val error429 = runCatching { source.popular(1) }.exceptionOrNull()
            assertTrue(error429 is IOException)
            assertTrue(error429!!.message!!.contains("busy"))

            respond("{}", 404)
            val error404 = runCatching { source.latest(1) }.exceptionOrNull()
            assertTrue(error404 is IOException)
            assertTrue(error404!!.message!!.contains("404"))
        }
    }
}
