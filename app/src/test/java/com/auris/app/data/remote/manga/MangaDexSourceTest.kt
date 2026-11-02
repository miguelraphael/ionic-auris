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

class MangaDexSourceTest {
    private val server = MockWebServer()
    private lateinit var source: MangaDexSource
    private val mangaId = "00000000-0000-0000-0000-000000000001"
    private val chapterId = "00000000-0000-0000-0000-000000000002"
    private val item get() = SourceItem(mangaId, "Test", null)

    @Before fun setup() {
        server.start()
        source = MangaDexSource(OkHttpClient(), server.url("/"), server.url("/"))
    }
    @After fun teardown() { server.shutdown() }
    private fun respond(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }
    private fun chapter(id: String, number: String?, title: String, pages: Int = 2, external: String = "null") =
        """{"id":"$id","attributes":{"volume":null,"chapter":${number?.let { "\"$it\"" } ?: "null"},"title":"$title","pages":$pages,"externalUrl":$external}}"""

    @Test fun searchEncodesQueryAndMapsCoverAndAuthor() = runBlocking {
        respond("""{"result":"ok","data":[{"id":"$mangaId","attributes":{"title":{"ja":"Fallback","en":"English title"},"year":2020},"relationships":[{"type":"cover_art","attributes":{"fileName":"cover.jpg"}},{"type":"author","attributes":{"name":"Author"}}]}],"total":100}""")
        val result = source.search(2, "A & B?", FilterList())
        val request = server.takeRequest().requestUrl!!
        assertEquals("A & B?", request.queryParameter("title"))
        assertEquals("30", request.queryParameter("offset"))
        assertEquals("en", request.queryParameter("availableTranslatedLanguage[]"))
        assertEquals(listOf("safe", "suggestive"), request.queryParameterValues("contentRating[]"))
        assertEquals("English title", result.items.single().title)
        assertEquals("Author", result.items.single().author)
        assertTrue(result.items.single().thumbnailUrl!!.endsWith("/covers/$mangaId/cover.jpg.256.jpg"))
        assertTrue(result.hasNextPage)
    }

    @Test fun paginatesChaptersWithoutRoundingFractionalOrRepeatedNumbers() = runBlocking {
        respond("""{"result":"ok","data":[${chapter(chapterId, "1", "First")},${chapter("00000000-0000-0000-0000-000000000003", "1.5", "Extra")}],"total":4}""")
        respond("""{"result":"ok","data":[${chapter("00000000-0000-0000-0000-000000000004", "1.5", "Other group")},${chapter("00000000-0000-0000-0000-000000000005", null, "Oneshot")}],"total":4}""")
        val result = source.units(item)
        assertEquals(listOf(1, 2, 3, 4), result.map { it.index })
        assertEquals(4, result.map { it.url }.distinct().size)
        assertTrue(result[1].title.contains("1.5"))
        val firstRequest = server.takeRequest().requestUrl!!
        assertEquals("0", firstRequest.queryParameter("includeExternalUrl"))
        assertEquals("0", firstRequest.queryParameter("includeFuturePublishAt"))
        assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("offset"))
    }

    @Test fun excludesExternalAndEmptyChapters() = runBlocking {
        respond("""{"result":"ok","data":[${chapter(chapterId, "1", "External", external = "\"https://publisher.test/read\"")},${chapter("empty", "2", "Empty", pages = 0)}],"total":2}""")
        assertTrue(source.units(item).isEmpty())
    }

    @Test fun resolvesFreshImageServerAndPreservesPageOrder() = runBlocking {
        repeat(2) { attempt ->
            respond("""{"result":"ok","baseUrl":"${server.url("/cdn$attempt")}","chapter":{"hash":"abc","data":["002.jpg","001.jpg"],"dataSaver":["small.jpg"]}}""")
            val pages = source.pages(item, SourceUnit(chapterId, 1, "First"))
            assertEquals(2, pages.size)
            assertTrue(pages[0].imageUrl!!.endsWith("/cdn$attempt/data/abc/002.jpg"))
            assertTrue(pages[1].imageUrl!!.endsWith("/cdn$attempt/data/abc/001.jpg"))
            assertEquals("/at-home/server/$chapterId", server.takeRequest().path)
        }
    }

    @Test fun rateLimitIsAnErrorRatherThanAnEmptyCatalogue() = runBlocking {
        respond("{}", 429)
        val error = runCatching { source.popular(1) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("wait"))
    }

    @Test fun chapterRefreshPreservesIdentityAndReadingProgress() = runBlocking {
        val entry = com.auris.app.data.local.entity.LibraryEntryEntity(id = "entry",
            mediaType = com.auris.app.core.model.MediaType.MANGA, sourceId = MangaDexSource.ID,
            sourceKey = mangaId, title = "Manga")
        val saved = com.auris.app.data.local.entity.LibraryUnitEntity("entry", 7, chapterId,
            "Old title", isWatched = true, lastPositionMs = 12, bookmark = 1)
        var persisted = emptyList<com.auris.app.data.local.entity.LibraryUnitEntity>()
        val registry = object : com.auris.app.source.ExtensionSourceRegistry {
            override val sources = emptyList<com.auris.app.source.LoadedSource>()
        }
        val repository = com.auris.app.data.repository.ExtensionContentRepository(
            registry, { _, chapters -> persisted = chapters }, { _, _ -> }, listOf(source), { listOf(saved) })
        respond("""{"result":"ok","data":[${chapter("00000000-0000-0000-0000-000000000003", "0.5", "New prequel")},${chapter(chapterId, "1", "New title")}],"total":2}""")
        assertTrue(repository.refreshUnits(entry).isSuccess)
        val original = persisted.first { it.sourceUnitId == chapterId }
        assertEquals(7, original.unitIndex)
        assertEquals(12L, original.lastPositionMs)
        assertTrue(original.isWatched)
        assertEquals(1, original.bookmark)
        assertEquals("Ch. 1 · New title", original.title)
        assertEquals(8, persisted.first { it.sourceUnitId != chapterId }.unitIndex)
    }
}
