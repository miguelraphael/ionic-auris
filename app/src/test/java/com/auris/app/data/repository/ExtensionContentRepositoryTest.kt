package com.auris.app.data.repository

import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.source.ExtensionSourceRegistry
import com.auris.app.source.LoadedSource
import com.auris.app.source.SourceUnit
import com.auris.app.source.StreamTarget
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extension content tests.
 *
 * The behaviour worth protecting is the failure surface. A library entry can outlive its
 * extension: uninstalled, disabled, or synced from another device. Every method has to
 * distinguish "this content is unavailable" from "this content is empty" from "the network
 * failed", because the UI message differs in each case and collapsing them tells the user
 * something false.
 */
class ExtensionContentRepositoryTest {

    private val registry = FakeRegistry()
    private var persistedUnits: List<LibraryUnitEntity>? = null
    private var failWrites = false

    private val logs = mutableListOf<String>()

    private val repository = ExtensionContentRepository(
        registry = registry,
        persistUnits = { _, units ->
            if (failWrites) throw java.io.IOException("disk full")
            persistedUnits = units
        },
        log = { tag, message -> logs += "$tag: $message" },
    )

    // ---- Resolution ---------------------------------------------------------

    @Test
    fun resolvesSourceByIdNotByName() {
        registry.add(id = 1000L, name = "Mock Anime")

        val source = repository.resolveSource(entry(sourceId = "1000"))

        // Aniyomi ids are MD5-derived and stable across renames; a name-based lookup would
        // silently detach every entry using a source the moment it was renamed.
        assertNotNull(source)
        assertEquals("Mock Anime", source!!.name)
    }

    @Test
    fun unknownSourceResolvesToNullRatherThanThrowing() {
        registry.add(id = 1000L, name = "Mock Anime")

        // The common real case: an extension was uninstalled after the entry was added.
        assertNull(repository.resolveSource(entry(sourceId = "9999")))
    }

    @Test
    fun noSourcesInstalledResolvesToNull() {
        assertNull(repository.resolveSource(entry(sourceId = "1000")))
    }

    @Test
    fun resolvedSourceTakesMediaTypeFromTheEntry() {
        registry.add(id = 1000L, name = "Mock Anime")

        // Under Anikku the source class is the same either way; presentation follows the entry.
        assertEquals(
            MediaType.ANIME,
            repository.resolveSource(entry(sourceId = "1000"))!!.mediaType,
        )
        assertEquals(
            MediaType.MANGA,
            repository.resolveSource(entry(sourceId = "1000", mediaType = MediaType.MANGA))!!.mediaType,
        )
    }

    @Test
    fun sourceIdIsMatchedAsAStringNotNumerically() {
        registry.add(id = 1234567890123L, name = "Big Id Source")

        // Kitsu/extension ids are 64-bit; parsing `sourceId` as Int would truncate and either
        // miss a valid source or match the wrong one.
        assertNotNull(repository.resolveSource(entry(sourceId = "1234567890123")))
    }

    // ---- Unit fetching ------------------------------------------------------

    @Test
    fun fetchesUnitsFromTheExtension() = runBlocking {
        registry.add(id = 1000L, units = listOf(unit(0, "/ep/1", "Episode 1"), unit(1, "/ep/2", "Episode 2")))

        val result = repository.fetchUnits(entry(sourceId = "1000"))

        assertTrue(result.isSuccess)
        assertEquals(listOf("Episode 1", "Episode 2"), result.getOrThrow().map { it.title })
    }

    @Test
    fun passesSourceKeyAndTitleBackToTheExtension() = runBlocking {
        val fake = registry.add(id = 1000L, units = listOf(unit(0, "/ep/1", "Episode 1")))

        repository.fetchUnits(
            entry(sourceId = "1000", sourceKey = "/anime/alpha", title = "Alpha Series"),
        )

        // The adapter needs both: the source-relative url to build a request, and the title to
        // survive its own internal bookkeeping.
        assertEquals("/anime/alpha", fake.lastItem?.url)
        assertEquals("Alpha Series", fake.lastItem?.title)
    }

    @Test
    fun missingSourceFailsWithUnavailableNotEmptyList() = runBlocking {
        val result = repository.fetchUnits(entry(sourceId = "9999"))

        // Empty would render as "no episodes listed", which is a false statement: the episodes
        // exist, the source providing them does not.
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ExtensionContentRepository.UnavailableException)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not installed"))
    }

    @Test
    fun networkFailurePropagatesUnwrapped() = runBlocking {
        registry.add(id = 1000L, unitsFailure = java.io.IOException("offline"))

        val result = repository.fetchUnits(entry(sourceId = "1000"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun emptyUnitListIsASuccessNotAFailure() = runBlocking {
        registry.add(id = 1000L, units = emptyList())

        val result = repository.fetchUnits(entry(sourceId = "1000"))

        // A show that has not aired yet genuinely has no episodes.
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    // ---- Persistence --------------------------------------------------------

    @Test
    fun refreshPersistsUnits() = runBlocking {
        registry.add(id = 1000L, units = listOf(unit(0, "/ep/1", "Episode 1")))

        repository.refreshUnits(entry(sourceId = "1000"))

        val saved = persistedUnits!!
        assertEquals(1, saved.size)
        assertEquals("Episode 1", saved.single().title)
        // The source-relative id, not the display title: titles get re-spelled, ids do not.
        assertEquals("/ep/1", saved.single().sourceUnitId)
        assertEquals("entry-1", saved.single().entryId)
    }

    @Test
    fun failedRefreshLeavesStoredUnitsAlone() = runBlocking {
        registry.add(id = 1000L, unitsFailure = java.io.IOException("offline"))

        repository.refreshUnits(entry(sourceId = "1000"))

        // Overwriting a good stored list with nothing turns a transient network failure into
        // data loss the user can see.
        assertNull(persistedUnits)
    }

    @Test
    fun emptyFetchDoesNotOverwriteStoredUnits() = runBlocking {
        registry.add(id = 1000L, units = emptyList())

        repository.refreshUnits(entry(sourceId = "1000"))

        assertNull(persistedUnits)
    }

    @Test
    fun refreshStillSucceedsWhenTheWriteFails() = runBlocking {
        registry.add(id = 1000L, units = listOf(unit(0, "/ep/1", "Episode 1")))
        failWrites = true

        // A failed persistence must not fail the fetch the caller is waiting on: the list still
        // displays, it just will not survive a restart.
        val result = repository.refreshUnits(entry(sourceId = "1000"))

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
    }

    // ---- Streams ------------------------------------------------------------

    @Test
    fun fetchesStreamsForAnime() = runBlocking {
        registry.add(
            id = 1000L,
            streams = listOf(StreamTarget(url = "https://cdn.test/a.m3u8")),
        )

        val result = repository.fetchStreams(entry(sourceId = "1000"), unit(0, "/ep/1", "Episode 1"))

        assertEquals("https://cdn.test/a.m3u8", result.getOrThrow().single().url)
    }

    @Test
    fun mangaExposesNoStreams() = runBlocking {
        registry.add(id = 1000L, streams = emptyList())

        val result = repository.fetchStreams(
            entry(sourceId = "1000", mediaType = MediaType.MANGA),
            unit(0, "/ch/1", "Chapter 1"),
        )

        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun streamFetchFailsCleanlyWithoutASource() = runBlocking {
        val result = repository.fetchStreams(entry(sourceId = "9999"), unit(0, "/ep/1", "Ep 1"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ExtensionContentRepository.UnavailableException)
    }

    // ---- Fixtures -----------------------------------------------------------

    private fun entry(
        sourceId: String = "1000",
        sourceKey: String = "/anime/alpha",
        title: String = "Alpha Series",
        mediaType: MediaType = MediaType.ANIME,
    ) = LibraryEntryEntity(
        id = "entry-1",
        remoteId = "remote-1",
        mediaType = mediaType,
        sourceId = sourceId,
        sourceKey = sourceKey,
        title = title,
        status = EntryStatus.IN_PROGRESS,
        totalUnits = 0,
    )

    private fun unit(index: Int, url: String, title: String) = SourceUnit(
        url = url,
        index = index,
        title = title,
    )

    /**
     * Scripted [ExtensionSourceRegistry].
     *
     * [com.auris.app.source.ExtensionLoader] loads APKs through `DexClassLoader` and owns a
     * private registry, which is exactly why the repository depends on the narrow registry
     * interface instead. What these tests cover is the repository's own logic — id matching,
     * persistence rules, failure classification — and the DexClassLoader path is already
     * covered by the instrumented `ExtensionLoaderTest`.
     */
    private class FakeRegistry : ExtensionSourceRegistry {
        private val entries = mutableMapOf<Long, FakeAnimeSource>()

        override val sources: List<LoadedSource>
            get() = entries.map { (id, fake) -> LoadedSource(fake, "com.auris.mock") }

        fun add(
            id: Long,
            name: String = "Mock Source",
            units: List<SourceUnit> = emptyList(),
            streams: List<StreamTarget> = emptyList(),
            unitsFailure: Throwable? = null,
        ): FakeAnimeSource = FakeAnimeSource(id, name, units, streams, unitsFailure)
            .also { entries[id] = it }
    }

    /**
     * Minimal [AnimeHttpSource].
     *
     * Extends [AnimeHttpSource] rather than implementing [AnimeSource] because that is what a
     * real extension does: `AnimeSourceAdapter` requires an `AnimeHttpSource` (it needs
     * `baseUrl` and the request/parse pair), and a fake implementing only `AnimeSource` would
     * be rejected at construction — testing a shape no real extension has.
     */
    private class FakeAnimeSource(
        override val id: Long,
        override val name: String,
        private val units: List<SourceUnit>,
        private val streams: List<StreamTarget>,
        private val unitsFailure: Throwable?,
    ) : eu.kanade.tachiyomi.animesource.online.AnimeHttpSource() {

        override val baseUrl: String = "https://fake.test"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        /** The last anime handed to the adapter, so tests can assert the round-trip. */
        var lastItem: SAnime? = null

        override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
            lastItem = anime
            unitsFailure?.let { throw it }
            return units.map { sourceUnit ->
                SEpisode.create().apply {
                    url = sourceUnit.url
                    name = sourceUnit.title
                    episode_number = sourceUnit.index.toFloat()
                }
            }
        }

        override suspend fun getVideoList(episode: SEpisode): List<Video> =
            streams.map { target ->
                VideoImpl().apply { url = target.url; quality = "auto" }
            }
    }
}
