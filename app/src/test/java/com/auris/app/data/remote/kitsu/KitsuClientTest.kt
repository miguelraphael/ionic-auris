package com.auris.app.data.remote.kitsu

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP-level tests against a real `MockWebServer`.
 *
 * These verify the *request* Kitsu receives, which is where the API's sharp edges live:
 * bracket-named query parameters, reserved characters in titles, and the 20-record cap. A
 * parsing test cannot catch a malformed URL, because the URL never reaches the parser.
 */
class KitsuClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KitsuClient

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        client = KitsuClient(
            client = OkHttpClient(),
            json = json,
            baseUrl = server.url("/api/edge").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun animeBody(canonicalTitle: String = "Sousou no Frieren", count: Int = 1) = """
        {"data":[{"id":"46474","type":"anime","attributes":{
          "canonicalTitle":"$canonicalTitle","status":"finished","episodeCount":28,
          "titles":{"en":"$canonicalTitle","ja_jp":"葬送のフリーレン"},
          "posterImage":{"large":"https://img.test/p.jpeg"}}}] ,
         "meta":{"count":$count}}
    """.trimIndent()

    // ---- Request construction ----------------------------------------------

    @Test
    fun sendsJsonApiAcceptHeader() = runBlocking {
        respond(animeBody())

        client.searchAnime("frieren")

        // Without this header Kitsu returns HTML, and the failure surfaces as a confusing
        // serialization error rather than a content-type mismatch.
        val request = server.takeRequest()
        assertEquals("application/vnd.api+json", request.getHeader("Accept"))
    }

    @Test
    fun encodesFilterTextParameter() = runBlocking {
        respond(animeBody())

        client.searchAnime("frieren")

        val path = server.takeRequest().path!!
        assertTrue("path was $path", path.contains("filter%5Btext%5D=frieren"))
    }

    @Test
    fun percentEncodesReservedCharactersInQuery() = runBlocking {
        respond(animeBody())

        // Every one of these breaks naive string concatenation. `Fate/Zero` unencoded makes the
        // rest of the path a second parameter; `Re:Zero` truncates at the fragment.
        client.searchAnime("Fate/Zero Re:Zero & Studio")

        val path = server.takeRequest().path!!
        assertTrue("path was $path", path.contains("Fate%2FZero"))
        assertTrue("path was $path", path.contains("Re%3AZero"))
        assertTrue("path was $path", path.contains("%26"))
        assertFalse("path was $path", path.contains("Re:Zero"))
    }

    @Test
    fun sendsPageLimitAndOffset() = runBlocking {
        respond(animeBody())

        client.searchAnime("frieren", limit = 5, offset = 10)

        val path = server.takeRequest().path!!
        assertTrue("path was $path", path.contains("page%5Blimit%5D=5"))
        assertTrue("path was $path", path.contains("page%5Boffset%5D=10"))
    }

    @Test
    fun clampsLimitToKitsuMaximum() = runBlocking {
        respond(animeBody())

        // Asking for 100 does not error — Kitsu silently returns 20, which looks like the
        // search simply found nothing more. Clamping makes the cap explicit in the request.
        client.searchAnime("frieren", limit = 500)

        val path = server.takeRequest().path!!
        assertTrue("path was $path", path.contains("page%5Blimit%5D=${KitsuClient.MAX_LIMIT}"))
    }

    @Test
    fun clampsNegativeOffset() = runBlocking {
        respond(animeBody())

        client.searchAnime("frieren", offset = -5)

        assertTrue(server.takeRequest().path!!.contains("page%5Boffset%5D=0"))
    }

    @Test
    fun sendsSortWhenGiven() = runBlocking {
        respond(animeBody())

        client.animeByGenres(listOf(1L, 4L), sort = KitsuSort.POPULARITY)

        val path = server.takeRequest().path!!
        // The API takes the attribute name, not a `-` prefixed direction variant.
        assertTrue("path was $path", path.contains("sort=popularityRank"))
        assertFalse("path was $path", path.contains("-popularityRank"))
    }

    @Test
    fun sendsGenreIdsCommaJoined() = runBlocking {
        respond(animeBody())

        client.animeByGenres(listOf(1L, 4L, 12L))

        // Ids, not names: passing names returns 0 results with no error.
        assertTrue(server.takeRequest().path!!.contains("filter%5Bgenres%5D=1%2C4%2C12"))
    }

    @Test
    fun requestsSingleResourceByIdPath() = runBlocking {
        respond("""{"data":{"id":"46474","type":"anime","attributes":{"canonicalTitle":"Frieren"}}}""")

        val result = client.anime(46474L)

        assertEquals("/api/edge/anime/46474", server.takeRequest().path)
        assertEquals("Frieren", result.getOrThrow().canonicalTitle)
    }

    @Test
    fun requestsMangaEndpointSeparately() = runBlocking {
        respond("""{"data":{"id":"8","type":"manga","attributes":{"chapterCount":374}}}""")

        val result = client.manga(8L)

        assertEquals("/api/edge/manga/8", server.takeRequest().path)
        assertEquals(374, result.getOrThrow().chapterCount)
    }

    @Test
    fun requestsGenresEndpointWithLargePageSize() = runBlocking {
        respond("""{"data":[{"id":"1","type":"genres","attributes":{"name":"Action"}}]}""")

        val genres = client.genres().getOrThrow()

        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/api/edge/genres"))
        // 62 genres exist; one page must fetch all of them.
        assertTrue("path was $path", path.contains("page%5Blimit%5D=100"))
        assertEquals("Action", genres.single().attributes.name)
    }

    // ---- Response handling --------------------------------------------------

    @Test
    fun parsesCollectionResponse() = runBlocking {
        respond(animeBody(count = 42))

        val page = client.searchAnime("frieren").getOrThrow()

        assertEquals(1, page.items.size)
        assertEquals("Sousou no Frieren", page.items.single().canonicalTitle)
        // `meta.count` is the total across all pages, not the page length — a genre filter
        // returned 22,494 on a page of one.
        assertEquals(42, page.totalCount)
        assertTrue(page.hasMore)
    }

    @Test
    fun parsesSingleResourceResponse() = runBlocking {
        respond("""{"data":{"id":"1","type":"anime","attributes":{"canonicalTitle":"Single"}}}""")

        val result = client.anime(1L).getOrThrow()

        assertEquals("Single", result.canonicalTitle)
    }

    @Test
    fun reportsHttpErrorWithKitsuMessage() = runBlocking {
        respond(
            """{"errors":[{"status":"400","code":"invalid_filter","title":"Invalid filter","detail":"Genre filter must be numeric"}]}""",
            code = 400,
        )

        val error = client.searchAnime("frieren").exceptionOrNull()

        // Kitsu's own detail is actionable; a bare 400 is not.
        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("Genre filter must be numeric"))
    }

    @Test
    fun reportsHttpErrorWithoutJsonBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("<html>down</html>"))

        val error = client.searchAnime("frieren").exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("503"))
    }

    @Test
    fun reportsEmptyBodyAsError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        assertTrue(client.searchAnime("frieren").exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun reportsUnparsableBodyAsErrorNotCrash() = runBlocking {
        respond("this is not json")

        val error = client.searchAnime("frieren").exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("Could not parse"))
    }

    @Test
    fun skipsMalformedRecordWithoutFailingThePage() = runBlocking {
        // One record whose attributes are the wrong shape. Dropping the whole page because of
        // it would blank a good result list.
        respond("""
            {"data":[
              {"id":"1","type":"anime","attributes":{"canonicalTitle":"Good"}},
              {"id":"2","type":"anime","attributes":{"episodeCount":"not-a-number"}},
              {"id":"3","type":"anime","attributes":{"canonicalTitle":"Also good"}}
            ],"meta":{"count":3}}
        """.trimIndent())

        val page = client.searchAnime("frieren").getOrThrow()

        assertEquals(listOf("Good", "Also good"), page.items.map { it.canonicalTitle })
    }

    @Test
    fun treatsEmptyDataArrayAsEmptyPageNotError() = runBlocking {
        respond("""{"data":[],"meta":{"count":0}}""")

        val page = client.searchAnime("zzzznotathing").getOrThrow()

        assertTrue(page.items.isEmpty())
        assertEquals(0, page.totalCount)
        assertFalse(page.hasMore)
    }

    @Test
    fun hasMoreReflectsRemainingRecords() = runBlocking {
        // The fixture yields 1 item, so `hasMore` must be derived from `offset + items.size`
        // against the server's total — not from `items.size == limit`, which fails whenever the
        // last page is short (and the common page size is capped at 20 by Kitsu anyway).
        respond(animeBody(count = 5))
        val firstPage = client.searchAnime("frieren", limit = 5, offset = 0).getOrThrow()
        assertTrue(firstPage.hasMore)

        respond(animeBody(count = 5))
        val lastPage = client.searchAnime("frieren", limit = 5, offset = 4).getOrThrow()
        assertFalse(lastPage.hasMore)
    }

    @Test
    fun surfacesErrorObjectsReturnedWithA200() = runBlocking {
        // Kitsu can return a 200 carrying an `errors` array; treating it as success would
        // produce an empty list and read as "no results".
        respond("""{"errors":[{"status":"400","detail":"Rate limit reached"}]}""")

        val error = client.searchAnime("frieren").exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("Rate limit reached"))
    }
}