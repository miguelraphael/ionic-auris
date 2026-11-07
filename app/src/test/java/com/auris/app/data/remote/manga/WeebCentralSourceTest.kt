package com.auris.app.data.remote.manga

import com.auris.app.source.SourceItem
import com.auris.app.source.SourceUnit
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WeebCentralSourceTest {

    private val articleHtml = """
        <article class="bg-base-300 flex gap-4 p-4">
            <section class="w-full">
                <a href="/series/01J76XY7E9FNDZ1DBBM6PBJPFK/One-Piece">
                    <picture>
                        <source srcset="https://temp.compsci88.com/cover/normal/01J76XY7E9FNDZ1DBBM6PBJPFK.webp" media="(min-width: 768px)">
                        <img src="https://temp.compsci88.com/cover/fallback/01J76XY7E9FNDZ1DBBM6PBJPFK.jpg">
                    </picture>
                </a>
                <span class="tooltip tooltip-bottom" data-tip="One Piece">
                    <a href="/series/01J76XY7E9FNDZ1DBBM6PBJPFK/One-Piece" class="line-clamp-1 link link-hover">One Piece</a>
                </span>
            </section>
        </article>
    """

    @Test
    fun parsesPopularCatalogueAndPagination() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("""
                <div>
                    $articleHtml
                    <button>Load More</button>
                </div>
            """))

            val popular = source.popular(1)
            assertEquals(1, popular.items.size)
            val item = popular.items.first()
            assertEquals("One Piece", item.title)
            assertEquals("/series/01J76XY7E9FNDZ1DBBM6PBJPFK/One-Piece", item.url)
            assertEquals("https://temp.compsci88.com/cover/normal/01J76XY7E9FNDZ1DBBM6PBJPFK.webp", item.thumbnailUrl)
            assertTrue(popular.hasNextPage)

            val req = server.takeRequest()
            val url = req.requestUrl!!
            assertEquals("Popularity", url.queryParameter("sort"))
            assertEquals("Descending", url.queryParameter("order"))
            assertEquals("Full Display", url.queryParameter("display_mode"))
            assertEquals("0", url.queryParameter("offset"))
            assertEquals("32", url.queryParameter("limit"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun parsesLatestUpdatesAndOffset() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("""<div>$articleHtml</div>"""))

            val latest = source.latest(2)
            assertEquals(1, latest.items.size)

            val req = server.takeRequest()
            val url = req.requestUrl!!
            assertEquals("Latest Updates", url.queryParameter("sort"))
            assertEquals("32", url.queryParameter("offset"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun searchSanitizesExcludedCharacters() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("""<div>$articleHtml</div>"""))

            val results = source.search(1, "One Piece: (Special, Edition!)", FilterList())
            assertEquals(1, results.items.size)

            val req = server.takeRequest()
            val queryText = req.requestUrl!!.queryParameter("text")
            assertEquals("One Piece   Special  Edition", queryText)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun detailsExtractsAuthorAndTitle() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("""
                <html>
                    <h1>One Piece</h1>
                    <ul>
                        <li>
                            <strong>Author(s): </strong>
                            <span><a href="/search?author=ODA+Eiichiro">ODA Eiichiro</a></span>
                        </li>
                    </ul>
                    <picture>
                        <source srcset="https://temp.compsci88.com/cover/normal/cover.webp">
                    </picture>
                </html>
            """))

            val item = SourceItem("/series/01J76XY7E9FNDZ1DBBM6PBJPFK/One-Piece", "Placeholder", null)
            val details = source.details(item)
            assertEquals("One Piece", details.title)
            assertEquals("ODA Eiichiro", details.author)
            assertEquals("https://temp.compsci88.com/cover/normal/cover.webp", details.thumbnailUrl)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unitsOrdersChaptersAscendingByReadingOrder() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("""
                <div>
                    <div class="flex items-center">
                        <a href="/chapters/ch2">
                            <span class="grow"><span>Chapter 2</span></span>
                            <time datetime="2026-03-24T15:22:16.903Z">March 24</time>
                        </a>
                    </div>
                    <div class="flex items-center">
                        <a href="/chapters/ch1">
                            <span class="grow"><span>Chapter 1</span></span>
                            <time datetime="2026-03-10T15:05:36.709Z">March 10</time>
                        </a>
                    </div>
                </div>
            """))

            val item = SourceItem("/series/01J76XY7E9FNDZ1DBBM6PBJPFK/One-Piece", "One Piece", null)
            val units = source.units(item)
            assertEquals(2, units.size)
            assertEquals("Chapter 1", units[0].title)
            assertEquals(1, units[0].index)
            assertEquals("/chapters/ch1", units[0].url)
            assertEquals("Chapter 2", units[1].title)
            assertEquals(2, units[1].index)
            assertEquals("/chapters/ch2", units[1].url)

            val req = server.takeRequest()
            assertEquals("/series/01J76XY7E9FNDZ1DBBM6PBJPFK/full-chapter-list", req.requestUrl!!.encodedPath)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pagesParsesImageStripUrlsWithReferer() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setBody("""
                <section id="chapter-images">
                    <img src="https://scans.example.com/page1.png" />
                    <img src="https://scans.example.com/page2.png" />
                </section>
            """))

            val item = SourceItem("/series/01J76XY7E9FNDZ1DBBM6PBJPFK/One-Piece", "One Piece", null)
            val unit = SourceUnit("/chapters/01KMG70ZY7V9NGC9NWCXSTD628", 1, "Chapter 1")
            val pages = source.pages(item, unit)
            assertEquals(2, pages.size)
            assertEquals("https://scans.example.com/page1.png", pages[0].imageUrl)
            assertEquals("https://scans.example.com/page2.png", pages[1].imageUrl)
            assertEquals(server.url("/").toString(), pages[0].headers["Referer"])

            val req = server.takeRequest()
            assertEquals("/chapters/01KMG70ZY7V9NGC9NWCXSTD628/images", req.requestUrl!!.encodedPath)
            assertEquals("False", req.requestUrl!!.queryParameter("is_prev"))
            assertEquals("long_strip", req.requestUrl!!.queryParameter("reading_style"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun handlesHttpErrorGracefully() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = WeebCentralSource(OkHttpClient(), server.url("/"))
            server.enqueue(MockResponse().setResponseCode(500))
            val result = runCatching { source.popular(1) }
            assertTrue(result.isFailure)
        } finally {
            server.shutdown()
        }
    }
}
