package com.auris.app.data.remote.manga

import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MangaPillSourceTest {
    @Test fun parsesTrendingSearchPaginationAndChapterImages() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = MangaPillSource(OkHttpClient(), server.url("/"))
            fun respond(body: String) { server.enqueue(MockResponse().setBody(body)) }
            val card = """<div><a href="/manga/3069/naruto"><img data-src="/poster.jpg"></a><div><a href="/manga/3069/naruto">Naruto</a></div></div>"""
            respond("<div><div><h4>Trending Mangas</h4></div><div>$card</div></div>")
            val popular = source.popular(1)
            assertEquals("Naruto", popular.items.single().title)
            assertFalse(popular.hasNextPage)
            server.takeRequest()
            respond("<form><input name=q></form>$card<a href='/search?q=Naruto&page=2'>Next</a>")
            val search = source.search(1, "Naruto & friends", FilterList())
            assertTrue(search.hasNextPage)
            assertEquals("Naruto & friends", server.takeRequest().requestUrl!!.queryParameter("q"))
            respond("""<div id=chapters><a href='/chapters/3069-10100000/chapter-100'>Chapter 100</a><a href='/chapters/3069-10001000/chapter-1'>Chapter 1</a></div>""")
            val units = source.units(popular.items.single())
            assertEquals(listOf("Chapter 1", "Chapter 100"), units.map { it.title })
            server.takeRequest()
            respond("""<img class=js-page data-src='/1.jpg'><img class=js-page data-src='/2.jpg'>""")
            val pages = source.pages(popular.items.single(), units.first())
            assertEquals(listOf(server.url("/1.jpg").toString(), server.url("/2.jpg").toString()), pages.map { it.imageUrl })
            assertEquals(server.url("/").toString(), pages.first().headers["Referer"])
        } finally { server.shutdown() }
    }

    @Test fun rejectsUnavailablePageInsteadOfReturningEmptySuccess() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("<html>Challenge</html>"))
            assertTrue(runCatching { MangaPillSource(OkHttpClient(), server.url("/")).latest(1) }.isFailure)
        } finally { server.shutdown() }
    }
}
