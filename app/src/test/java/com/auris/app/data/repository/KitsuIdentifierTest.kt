package com.auris.app.data.repository

import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.remote.kitsu.KitsuDataSource
import com.auris.app.data.remote.kitsu.KitsuPage
import com.auris.app.data.remote.kitsu.KitsuResource
import com.auris.app.data.remote.kitsu.KitsuAnimeAttributes
import com.auris.app.data.remote.kitsu.KitsuMangaAttributes
import com.auris.app.data.remote.kitsu.KitsuImage
import com.auris.app.data.remote.kitsu.KitsuTitles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identifier tests.
 *
 * Two properties matter more than the happy path:
 *
 *  1. **A failed lookup never damages the entry.** Metadata is enrichment. If a Kitsu outage
 *     could wipe an existing title or description, enrichment would become a liability.
 *  2. **The stored `kitsuId` is Kitsu's real id.** An earlier version of the client decoded
 *     straight to attributes and lost the id, and the identifier then synthesised one from the
 *     array index — which would have silently persisted `kitsuId = "0"` for every anime in the
 *     app. [storesKitsuRealIdRatherThanAPositionalIndex] is the regression test for that.
 */
class KitsuIdentifierTest {

    private val client = RecordingKitsuSource()
    private val writes = mutableListOf<LibraryEntryEntity>()
    private var failWrites = false

    private val identifier = KitsuIdentifier(
        client = client,
        write = { entry: LibraryEntryEntity ->
            if (failWrites) throw java.io.IOException("disk full")
            writes += entry
        },
        // Collected rather than logged: `android.util.Log` throws in plain JVM tests, and these
        // failures are silent by design, so the messages are worth asserting on.
        log = { tag, message -> logs += "$tag: $message" },
    )

    private val logs = mutableListOf<String>()

    /** The most recently written entry, or null when nothing was written. */
    private val lastUpsert: LibraryEntryEntity? get() = writes.lastOrNull()

    private fun entry(
        title: String = "Sousou no Frieren",
        mediaType: MediaType = MediaType.ANIME,
        kitsuId: String? = null,
        kitsuOriginalTitle: String? = null,
        description: String? = null,
        artworkUrl: String? = null,
        kitsuCoverUrl: String? = null,
        genres: List<String> = emptyList(),
    ) = LibraryEntryEntity(
        id = "entry-1",
        remoteId = "remote-1",
        mediaType = mediaType,
        sourceId = "1000",
        sourceKey = "/anime/frieren",
        title = title,
        description = description,
        artworkUrl = artworkUrl,
        kitsuId = kitsuId,
        kitsuOriginalTitle = kitsuOriginalTitle,
        kitsuCoverUrl = kitsuCoverUrl,
        genres = genres,
        status = EntryStatus.IN_PROGRESS,
        totalUnits = 28,
    )

    // ---- Successful identification -----------------------------------------

    @Test
    fun storesKitsuRealIdRatherThanAPositionalIndex() = runBlocking {
        client.animeResponses["Sousou no Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "Sousou no Frieren"))

        val saved = lastUpsert!!
        assertEquals("The Kitsu id must be the server's, not the result's position",
            "46474", saved.kitsuId)
    }

    @Test
    fun writesArtworkSynopsisAndRating() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "Frieren"))

        val saved = lastUpsert!!
        assertEquals("https://img.test/poster.jpeg", saved.artworkUrl)
        assertEquals("https://img.test/cover.jpeg", saved.kitsuCoverUrl)
        assertEquals("葬送のフリーレン", saved.kitsuOriginalTitle)
        assertEquals(88.84, saved.kitsuRating!!, 0.001)
    }

    @Test
    fun keepsTheExtensionsOwnTitle() = runBlocking {
        client.animeResponses["My Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "My Frieren"))

        // The user added this entry under the extension's title; renaming their library
        // silently would be worse than never matching Kitsu at all.
        assertEquals("My Frieren", lastUpsert!!.title)
    }

    @Test
    fun doesNotOverwriteExistingSynopsis() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "Frieren", description = "Extension text"))

        // Kitsu's synopsis is better, but replacing a populated field is a visible edit and
        // belongs to an explicit refresh action.
        assertEquals("Extension text", lastUpsert!!.description)
    }

    @Test
    fun replacesArtworkWithKitsuPosterWhenAvailable() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "Frieren", artworkUrl = "https://local/old.jpg"))

        // The one field identification *is* allowed to replace: extension artwork is often a
        // dead CDN link, and Kitsu's 3:4 poster is a strict improvement in a grid cell.
        assertEquals("https://img.test/poster.jpeg", lastUpsert!!.artworkUrl)
    }

    @Test
    fun keepsExtensionGenresWhenPresent() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "Frieren", genres = listOf("Scraped")))

        assertEquals(listOf("Scraped"), lastUpsert!!.genres)
    }

    @Test
    fun identifiesMangaThroughTheMangaEndpoint() = runBlocking {
        client.mangaResponses["Berserk"] = Result.success(mangaPage())

        identifier.identifyAndPersist(entry(title = "Berserk", mediaType = MediaType.MANGA))

        assertEquals(1, client.mangaQueries.size)
        assertEquals(0, client.animeQueries.size)
        assertEquals("8", lastUpsert!!.kitsuId)
    }

    @Test
    fun neverIdentifiesMusic() = runBlocking {
        val result = identifier.identifyAndPersist(
            entry(title = "Kid Cudi", mediaType = MediaType.MUSIC),
        )

        // Kitsu has no music catalogue. A fabricated match here would attach a random anime's
        // cover art to somebody's album.
        assertNull(result)
        assertNull(lastUpsert)
        assertEquals(0, client.animeQueries.size + client.mangaQueries.size)
    }

    // ---- Re-identification --------------------------------------------------

    @Test
    fun refetchesByStoredIdWithoutSearching() = runBlocking {
        client.byIdAnime[46474L] = animeAttributes()
        val existing = entry(title = "Whatever the extension calls it now", kitsuId = "46474")

        identifier.findBestMatch(existing)

        // An id is exact; a text search could now match a sequel or a remake.
        assertEquals(1, client.byIdAnimeLookups.size)
        assertEquals(0, client.animeQueries.size)
    }

    @Test
    fun fallsBackToSearchWhenStoredIdNoLongerResolves() = runBlocking {
        client.byIdAnimeFailures += 46474L
        client.animeResponses["Frieren"] = Result.success(animePage())

        val match = identifier.findBestMatch(entry(title = "Frieren", kitsuId = "46474"))

        assertNotNull("a stale id must not permanently disable identification", match)
        assertEquals(1, client.animeQueries.size)
    }

    @Test
    fun prefersStoredOriginalTitleAsTheSearchQuery() = runBlocking {
        client.animeResponses["葬送のフリーレン"] = Result.success(animePage())

        identifier.findBestMatch(
            entry(title = "Frieren", kitsuOriginalTitle = "葬送のフリーレン"),
        )

        // The recorded canonical form is the one most likely to be indexed.
        assertEquals(listOf("葬送のフリーレン"), client.animeQueries)
    }

    // ---- Caching ------------------------------------------------------------

    @Test
    fun repeatedSearchesAreServedFromCache() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())

        identifier.findBestMatch(entry(title = "Frieren"))
        identifier.findBestMatch(entry(title = "Frieren"))
        identifier.findBestMatch(entry(title = "Frieren"))

        // Kitsu allows ~3 req/s unauthenticated; re-opening a screen must not cost a request.
        assertEquals(1, client.animeQueries.size)
    }

    @Test
    fun distinctQueriesEachCostOneRequest() = runBlocking {
        client.animeResponses["A"] = Result.success(animePage())
        client.animeResponses["B"] = Result.success(animePage())

        identifier.findBestMatch(entry(title = "A"))
        identifier.findBestMatch(entry(title = "B"))

        assertEquals(2, client.animeQueries.size)
    }

    @Test
    fun failedSearchesAreCachedSoOutagesAreNotRetriedInALoop() = runBlocking {
        client.animeFailures["Frieren"] = java.io.IOException("503")

        assertNull(identifier.findBestMatch(entry(title = "Frieren")))
        assertNull(identifier.findBestMatch(entry(title = "Frieren")))

        // Hammering a service that is already struggling is how a transient outage becomes a
        // sustained one.
        assertEquals(1, client.animeQueries.size)
    }

    // ---- Failure must not damage the entry ---------------------------------

    @Test
    fun lookupFailureLeavesEntryUntouched() = runBlocking {
        client.animeFailures["Frieren"] = java.io.IOException("offline")

        val result = identifier.identifyAndPersist(entry(title = "Frieren"))

        assertNull(result)
        assertNull("a failed lookup must not write a partial entry", lastUpsert)
    }

    @Test
    fun emptyResultsPersistNothing() = runBlocking {
        client.animeResponses["zzzz"] = Result.success(
            KitsuPage<KitsuAnimeAttributes>(resources = emptyList(), totalCount = 0, limit = 10, offset = 0),
        )

        assertNull(identifier.identifyAndPersist(entry(title = "zzzz")))
        assertNull(lastUpsert)
    }

    @Test
    fun failureIsExplainedInDiagnostics() = runBlocking {
        client.animeFailures["Frieren"] = java.io.IOException("offline")

        identifier.identifyAndPersist(entry(title = "Frieren"))

        // The write is deliberately silent to callers, so the log is the only record that
        // anything was attempted. Without it, a permanently-unidentified library is
        // undiagnosable.
        assertTrue(
            "diagnostics were $logs",
            logs.any { it.contains("Frieren") || it.contains("Kitsu search failed") },
        )
    }

    @Test
    fun writeFailureDoesNotThrow() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())
        failWrites = true

        // A failed write must not propagate: the caller is a UI loading path, and an exception
        // there would discard the whole screen's data.
        assertNull(identifier.identifyAndPersist(entry(title = "Frieren")))
    }

    @Test
    fun blankTitleIsNotSearched() = runBlocking {
        assertNull(identifier.findBestMatch(entry(title = "   ")))
        assertEquals(0, client.animeQueries.size)
    }

    // ---- Ranking ------------------------------------------------------------

    @Test
    fun rankCandidatesReturnsBestFirst() = runBlocking {
        client.animeResponses["Sousou no Frieren"] = Result.success(animePage())

        val ranked = identifier.rankCandidates("Sousou no Frieren", MediaType.ANIME)

        assertTrue(ranked.isNotEmpty())
        assertEquals(46474L, ranked.first().metadata.kitsuId)
    }

    @Test
    fun rankCandidatesIsEmptyForMusic() = runBlocking {
        assertTrue(identifier.rankCandidates("Anything", MediaType.MUSIC).isEmpty())
        assertEquals(0, client.animeQueries.size)
    }

    @Test
    fun rankCandidatesHandlesBlankQuery() = runBlocking {
        assertTrue(identifier.rankCandidates("  ", MediaType.ANIME).isEmpty())
    }

    // ---- Persistence behaviour ---------------------------------------------

    @Test
    fun existingKitsuFieldsSurviveWhenTheMatchLacksThem() = runBlocking {
        // Kitsu returned a record with no cover art. The entry already had one; identification
        // must not erase it.
        client.animeResponses["Frieren"] = Result.success(
            KitsuPage(
                resources = listOf(
                    KitsuResource(
                        id = 46474L,
                        attributes = animeAttributes(
                            cover = null,
                            poster = null,
                            rating = null,
                            originalTitle = null,
                        ),
                    ),
                ),
                totalCount = 1, limit = 10, offset = 0,
            ),
        )

        identifier.identifyAndPersist(
            entry(
                title = "Frieren",
                artworkUrl = "https://local/kept.jpg",
                kitsuCoverUrl = "https://local/kept-cover.jpg",
            ),
        )

        val saved = lastUpsert!!
        assertEquals("46474", saved.kitsuId)
        // Kitsu returned no artwork at all, so both stored values must survive untouched.
        assertEquals("https://local/kept-cover.jpg", saved.kitsuCoverUrl)
        assertEquals("https://local/kept.jpg", saved.artworkUrl)
        assertNull(saved.kitsuRating)
    }

    @Test
    fun identificationDoesNotMarkTheEntryForSync() = runBlocking {
        client.animeResponses["Frieren"] = Result.success(animePage())

        identifier.identifyAndPersist(entry(title = "Frieren"))

        // Enrichment is local. Pushing it to Supabase on every open would be a write storm.
        assertEquals(SyncState.LOCAL_ONLY, lastUpsert!!.syncState)
    }

    // ---- Fakes --------------------------------------------------------------

    /**
     * Scripted [KitsuDataSource].
     *
     * Records every query so the tests can assert on *request* behaviour — caching, endpoint
     * choice, "did not search at all" — which is where this class's real logic lives. A
     * MockWebServer would only prove the client talks HTTP, which is already covered separately.
     */
    private class RecordingKitsuSource : KitsuDataSource {
        val animeQueries = mutableListOf<String>()
        val mangaQueries = mutableListOf<String>()
        val byIdAnimeLookups = mutableListOf<Long>()

        val animeResponses = mutableMapOf<String, Result<KitsuPage<KitsuAnimeAttributes>>>()
        val mangaResponses = mutableMapOf<String, Result<KitsuPage<KitsuMangaAttributes>>>()
        val animeFailures = mutableMapOf<String, Throwable>()
        val mangaFailures = mutableMapOf<String, Throwable>()
        val byIdAnime = mutableMapOf<Long, KitsuAnimeAttributes>()
        val byIdAnimeFailures = mutableSetOf<Long>()

        override suspend fun searchAnime(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<KitsuPage<KitsuAnimeAttributes>> {
            animeQueries += query
            animeFailures[query]?.let { return Result.failure(it) }
            return animeResponses[query] ?: Result.success(
                KitsuPage(resources = emptyList(), totalCount = 0, limit = limit, offset = offset),
            )
        }

        override suspend fun searchManga(
            query: String,
            limit: Int,
            offset: Int,
        ): Result<KitsuPage<KitsuMangaAttributes>> {
            mangaQueries += query
            mangaFailures[query]?.let { return Result.failure(it) }
            return mangaResponses[query] ?: Result.success(
                KitsuPage(resources = emptyList(), totalCount = 0, limit = limit, offset = offset),
            )
        }

        override suspend fun anime(id: Long): Result<KitsuAnimeAttributes> {
            byIdAnimeLookups += id
            if (id in byIdAnimeFailures) {
                return Result.failure(java.io.IOException("404"))
            }
            return byIdAnime[id]
                ?.let { Result.success(it) }
                ?: Result.failure(java.io.IOException("404 for $id"))
        }

        override suspend fun manga(id: Long): Result<KitsuMangaAttributes> =
            Result.failure(java.io.IOException("not scripted"))
    }

    // ---- Fixtures -----------------------------------------------------------

    private fun animeAttributes(
        cover: String? = "https://img.test/cover.jpeg",
        poster: String? = "https://img.test/poster.jpeg",
        rating: Double? = 88.84,
        originalTitle: String? = "葬送のフリーレン",
    ) = KitsuAnimeAttributes(
        canonicalTitle = "Sousou no Frieren",
        titles = KitsuTitles(
            en = "Frieren: Beyond Journey's End",
            jaJp = originalTitle,
            enJp = "Sousou no Frieren",
        ),
        synopsis = "A synopsis.",
        status = "finished",
        ageRating = "PG",
        averageRating = rating,
        startDate = "2023-09-29",
        episodeCount = 28,
        posterImage = poster?.let { KitsuImage(large = it) },
        coverImage = cover?.let {
            KitsuImage(large = it)
        },
    )

    private fun animePage() = KitsuPage(
        resources = listOf(
            com.auris.app.data.remote.kitsu.KitsuResource(46474L, animeAttributes()),
            KitsuResource(
                99999L,
                animeAttributes().copy(canonicalTitle = "Sousou no Frieren: Sequel"),
            ),
        ),
        totalCount = 2,
        limit = 10,
        offset = 0,
    )

    private fun mangaPage() = KitsuPage(
        resources = listOf(
            KitsuResource(
                8L,
                KitsuMangaAttributes(
                    canonicalTitle = "Berserk",
                    titles = KitsuTitles(en = "Berserk", jaJp = "ベルセルク"),
                    status = "on_hold",
                    chapterCount = 374,
                    posterImage = KitsuImage(
                        large = "https://img.test/b.jpg",
                    ),
                ),
            ),
        ),
        totalCount = 1,
        limit = 10,
        offset = 0,
    )
}