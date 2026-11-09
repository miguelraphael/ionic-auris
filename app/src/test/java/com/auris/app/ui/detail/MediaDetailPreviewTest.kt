package com.auris.app.ui.detail

import androidx.lifecycle.viewModelScope
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.local.entity.SyncSessionEntity
import com.auris.app.data.remote.kitsu.KitsuAnimeAttributes
import com.auris.app.data.remote.kitsu.KitsuDataSource
import com.auris.app.data.remote.kitsu.KitsuImage
import com.auris.app.data.remote.kitsu.KitsuMangaAttributes
import com.auris.app.data.remote.kitsu.KitsuPage
import com.auris.app.data.remote.kitsu.KitsuResource
import com.auris.app.data.remote.kitsu.KitsuTitles
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.KitsuIdentifier
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.data.sync.SyncScheduler
import com.auris.app.source.*
import com.auris.app.ui.source.PreviewDetail
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MediaDetailPreviewTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val upserts = mutableListOf<LibraryEntryEntity>()
    private val kitsuWrites = mutableListOf<LibraryEntryEntity>()
    private val persistedUnits = mutableListOf<List<LibraryUnitEntity>>()
    private var updateProgressCalls = 0
    private var updateUnitProgressCalls = 0
    private var sessionUserId: String? = "user-1"
    private var vm: MediaDetailViewModel? = null

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { vm?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    @Test fun previewShowsTransientKitsuRatingWithoutPersisting() = runBlocking {
        val model = createPreview()
        withTimeout(5000) { model.state.first { it.averageRating != null } }
        withTimeout(5000) { model.state.first { !it.isUnitsLoading && it.units.isNotEmpty() } }

        val state = model.state.value
        assertTrue(state.isPreview)
        assertFalse(state.isSaved)
        assertEquals("Frieren", state.title)
        assertEquals("A synopsis.", state.synopsis)
        assertEquals(87.3, state.averageRating!!, 0.001)
        assertEquals("https://img.test/poster.jpeg", state.posterUrl)
        assertEquals("https://img.test/cover.jpeg", state.coverUrl)
        assertEquals(2, state.units.size)
        // Fetch-only: nothing reached Room until an explicit Save.
        assertTrue("preview fetch must not upsert", upserts.isEmpty())
        assertTrue("preview fetch must not persist units", persistedUnits.isEmpty())
    }

    @Test fun savePreviewPersistsAndFlipsSaved() = runBlocking {
        val model = createPreview()
        withTimeout(5000) { model.state.first { it.averageRating != null } }

        model.savePreview()
        withTimeout(5000) { model.state.first { it.isSaved } }

        assertEquals(1, upserts.size)
        val saved = upserts.single()
        assertEquals("Frieren", saved.title)
        assertEquals("test-anime", saved.sourceId)
        assertEquals(87.3, saved.kitsuRating!!, 0.001)
        assertEquals("46474", saved.kitsuId)
        assertEquals("A synopsis.", saved.description)
        val state = model.state.value
        assertTrue(state.isSaved)
        assertNotNull(state.savedEntryId)
        assertTrue("units must be persisted on save", persistedUnits.isNotEmpty())
    }

    @Test fun savePreviewSurfacesNotSignedIn() = runBlocking {
        sessionUserId = null
        val model = createPreview()
        withTimeout(5000) { model.state.first { it.averageRating != null } }

        model.savePreview()
        withTimeout(5000) { model.state.first { it.message != null } }

        assertFalse(model.state.value.isSaved)
        assertEquals("Not signed in", model.state.value.message)
        assertTrue(upserts.isEmpty())
    }

    @Test fun previewAnimeUnitClickNavigatesWithoutSaving() = runBlocking {
        val model = createPreview()
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }

        var played = false
        var navigated: Int? = null
        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = { navigated = it },
            onPlay = { _, _ -> played = true },
        )
        assertTrue("unsaved anime preview must navigate", played)
        assertNull(navigated)
        assertTrue("transient play must not upsert", upserts.isEmpty())
        assertEquals(0, updateProgressCalls)
        assertEquals(0, updateUnitProgressCalls)

        model.savePreview()
        withTimeout(5000) { model.state.first { it.isSaved } }
        var playedAfterSave = false
        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = {},
            onPlay = { _, _ -> playedAfterSave = true },
        )
        assertTrue(playedAfterSave)
    }

    @Test fun previewMangaUnitClickNavigatesWithoutSaving() = runBlocking {
        val model = createPreview(mediaType = MediaType.MANGA)
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }
        assertFalse(model.state.value.isSaved)

        var navigatedIndex: Int? = null
        var played = false
        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = { navigatedIndex = it },
            onPlay = { _, _ -> played = true },
        )
        assertTrue("unsaved manga preview must navigate", navigatedIndex != null)
        assertFalse(played)
        assertTrue("transient read must not upsert", upserts.isEmpty())
        assertEquals(0, updateProgressCalls)
        assertEquals(0, updateUnitProgressCalls)
    }

    @Test fun previewMusicUnitClickRemainsSaveGated() = runBlocking {
        val model = createPreview(mediaType = MediaType.MUSIC)
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }

        var played = false
        var navigated = false
        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = { navigated = true },
            onPlay = { _, _ -> played = true },
        )
        assertFalse("unsaved music preview must be inert", played)
        assertFalse(navigated)

        model.savePreview()
        withTimeout(5000) { model.state.first { it.isSaved } }
        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = { navigated = true },
            onPlay = { _, _ -> played = true },
        )
        assertTrue(played)
    }

    @Test fun unsavedPreviewUnitClickMakesNoUpsertAndNoProgressWrite() = runBlocking {
        val model = createPreview(mediaType = MediaType.MANGA)
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }

        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = {},
            onPlay = { _, _ -> },
        )
        assertFalse(model.state.value.isSaved)
        assertTrue(upserts.isEmpty())
        assertTrue(persistedUnits.isEmpty())
        assertEquals(0, updateProgressCalls)
        assertEquals(0, updateUnitProgressCalls)
    }

    @Test fun previewOfAlreadySavedTitleShowsSavedState() = runTest {
        val existing = savedRow()
        val model = createPreview(existing = existing)

        // Pump the scheduler so checkSavedPreview() lands deterministically.
        withTimeout(5000) { testScheduler.advanceUntilIdle(); model.state.value }
        val state = model.state.value
        // Still the preview route, but already saved: no Save button.
        assertTrue(state.isPreview)
        assertTrue(state.isSaved)
        assertEquals(existing.id, state.savedEntryId)
        assertEquals("Saved to Library ✓", state.message)
        assertTrue("preview entry must not upsert", upserts.isEmpty())
    }

    @Test fun savePreviewOnAlreadySavedTitleDoesNotDuplicate() = runTest {
        val existing = savedRow()
        val model = createPreview(existing = existing)

        model.savePreview()
        testScheduler.advanceUntilIdle()
        withTimeout(5000) { model.state.first { it.isSaved } }

        assertTrue("already-saved row must not upsert again", upserts.isEmpty())
        assertTrue(model.state.value.isSaved)
        assertEquals(existing.id, model.state.value.savedEntryId)
    }

    @Test fun savedRouteProjectsPersistedRating() = runBlocking {
        val model = createSaved(savedEntry(kitsuRating = 92.5))
        withTimeout(5000) { model.state.first { it.title == "Frieren" } }

        val state = model.state.value
        assertEquals(92.5, state.averageRating!!, 0.001)
        assertTrue("gate must stay closed when rating already persisted", kitsuWrites.isEmpty())
    }

    @Test fun savedRouteDerivesSavedMessage() = runBlocking {
        val model = createSaved(savedEntry(kitsuRating = 92.5))
        withTimeout(5000) { model.state.first { it.title == "Frieren" } }

        val state = model.state.value
        assertTrue(state.isSaved)
        assertEquals("saved-row-id", state.savedEntryId)
        assertEquals("Saved to Library ✓", state.message)
    }

    @Test fun ratingNullEntryIsRepairedAndPersisted() = runBlocking {
        val model = createSaved(savedEntry(kitsuRating = null))
        withTimeout(5000) { model.state.first { it.averageRating != null } }

        val written = kitsuWrites.single()
        assertEquals(87.3, written.kitsuRating!!, 0.001)
        assertEquals(87.3, model.state.value.averageRating!!, 0.001)
    }

    @Test fun watchedUnitMapsReadStateToSourceUnit() = runBlocking {
        val model = createSaved(
            savedEntry(kitsuRating = 92.5).copy(progress = 1f),
            storedUnits = listOf(
                LibraryUnitEntity(
                    entryId = "saved-row-id",
                    unitIndex = 1,
                    sourceUnitId = "ep-1",
                    title = "Ep 1",
                    durationMs = 1_800_000L,
                    isWatched = true,
                    lastPositionMs = 1_800_000L,
                ),
            ),
        )
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }

        val unit = model.state.value.units.single()
        assertTrue(unit.isWatched)
        assertEquals(1_800_000L, unit.watchPositionMs)
        assertEquals(1_800_000L, unit.watchDurationMs)
    }

    @Test fun inProgressUnitMapsReadStateToSourceUnit() = runBlocking {
        val model = createSaved(
            savedEntry(kitsuRating = 92.5).copy(progress = 0.3f),
            storedUnits = listOf(
                LibraryUnitEntity(
                    entryId = "saved-row-id",
                    unitIndex = 2,
                    sourceUnitId = "ep-2",
                    title = "Ep 2",
                    durationMs = 1_800_000L,
                    isWatched = false,
                    lastPositionMs = 45_000L,
                ),
            ),
        )
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }

        val state = model.state.value
        val unit = state.units.single()
        assertFalse(unit.isWatched)
        assertEquals(45_000L, unit.watchPositionMs)
        assertEquals(1_800_000L, unit.watchDurationMs)
        assertEquals(0.3f, state.entryProgress, 0.001f)
    }

    @Test fun unwatchedUnitMapsReadStateToSourceUnit() = runBlocking {
        val model = createSaved(
            savedEntry(kitsuRating = 92.5),
            storedUnits = listOf(
                LibraryUnitEntity(
                    entryId = "saved-row-id",
                    unitIndex = 3,
                    sourceUnitId = "ch-3",
                    title = "Chapter 3",
                    durationMs = null,
                    isWatched = false,
                    lastPositionMs = 0L,
                ),
            ),
        )
        withTimeout(5000) { model.state.first { it.units.isNotEmpty() } }

        val unit = model.state.value.units.single()
        assertFalse(unit.isWatched)
        assertEquals(0L, unit.watchPositionMs)
        assertNull(unit.watchDurationMs)
    }

    @Test fun previewShowsEnglishTitleFromKitsu() = runBlocking {
        val model = createPreview()
        withTimeout(5000) { model.state.first { it.englishTitle != null } }

        val state = model.state.value
        assertEquals("Frieren: Beyond Journey's End", state.englishTitle)
        assertEquals("フリーレン", state.originalTitle)
    }

    @Test fun previewWithoutEnglishTitleFallsBackToJapaneseOnly() = runBlocking {
        val model = createPreview(
            kitsu = FakeKitsu(
                KitsuAnimeAttributes(
                    canonicalTitle = "Sousou no Frieren",
                    titles = KitsuTitles(jaJp = "フリーレン"),
                    synopsis = "A synopsis.",
                    status = "finished",
                    subtype = "TV",
                    ageRating = "PG",
                    averageRating = 87.3,
                    startDate = "2023-09-29",
                    episodeCount = 28,
                    posterImage = KitsuImage(large = "https://img.test/poster.jpeg"),
                    coverImage = KitsuImage(large = "https://img.test/cover.jpeg"),
                ),
            ),
        )
        withTimeout(5000) { model.state.first { it.averageRating != null } }

        val state = model.state.value
        assertNull("no English locale means no English line", state.englishTitle)
        assertEquals("フリーレン", state.originalTitle)
    }

    @Test fun savedRouteShowsEnglishTitleFetchOnly() = runBlocking {
        val model = createSaved(savedEntry(kitsuRating = 92.5))
        withTimeout(5000) { model.state.first { it.englishTitle != null } }

        val state = model.state.value
        assertEquals("Frieren: Beyond Journey's End", state.englishTitle)
        assertTrue("English fetch must not persist", kitsuWrites.isEmpty())
    }

    @Test fun unitClickEnabledForAlreadySavedPreview() = runTest {
        val existing = savedRow()
        val model = createPreview(existing = existing)
        withContext(Dispatchers.Default) {
            withTimeout(5000) { model.state.first { it.isSaved && it.units.isNotEmpty() } }
        }

        var played = false
        model.onUnitClick(
            unit = model.state.value.units.first(),
            onNavigateToUnit = {},
            onPlay = { _, _ -> played = true },
        )
        assertTrue("saved preview must not be inert", played)
    }

    private fun savedRow() = LibraryEntryEntity(
        id = "saved-row-id",
        mediaType = MediaType.ANIME,
        sourceId = "test-anime",
        sourceKey = "/anime/frieren",
        title = "Frieren",
    )

    private fun savedEntry(kitsuRating: Double?) = LibraryEntryEntity(
        id = "saved-row-id",
        mediaType = MediaType.ANIME,
        sourceId = "test-anime",
        sourceKey = "/anime/frieren",
        title = "Frieren",
        description = "A synopsis.",
        kitsuId = "46474",
        kitsuRating = kitsuRating,
        artworkUrl = "https://img.test/poster.jpeg",
    )

    private fun createSaved(
        entry: LibraryEntryEntity,
        kitsu: KitsuDataSource = FakeKitsu(),
        storedUnits: List<LibraryUnitEntity> = emptyList(),
    ): MediaDetailViewModel {
        val entryFlow = MutableStateFlow<LibraryEntryEntity?>(entry)
        val libraryDao = proxy<LibraryDao> { method, args ->
            when (method) {
                "observeById" -> entryFlow
                "observeUnits" -> MutableStateFlow(storedUnits)
                "findById" -> entryFlow.value
                "findBySourceIdentity" -> entryFlow.value
                "belongsToOtherOwner" -> false
                "upsert" -> { upserts += (args.first() as LibraryEntryEntity); Unit }
                "updateProgress" -> { updateProgressCalls++; Unit }
                "updateUnitProgress" -> { updateUnitProgressCalls++; Unit }
                else -> error("Unexpected DAO call: $method")
            }
        }
        val syncDao = proxy<SyncDao> { method, _ ->
            when (method) {
                "currentSession" -> sessionUserId?.let { SyncSessionEntity(userId = it) }
                "enqueue" -> 1L
                else -> error("Unexpected sync call: $method")
            }
        }
        val context = android.content.ContextWrapper(null)
        val library = LibraryRepository(libraryDao, syncDao, SyncScheduler(context), Dispatchers.Unconfined, Json, requestSync = {})
        val source = object : MediaSource {
            override val id = "test-anime"
            override val name = "Test Anime"
            override val mediaType = MediaType.ANIME
            override val baseUrl = "https://example.test"
            override suspend fun configuration() = emptyMap<String, String>()
            override suspend fun updateConfiguration(values: Map<String, String>) = Unit
            override suspend fun popular(page: Int) = SourcePage(emptyList(), false)
            override suspend fun latest(page: Int) = popular(page)
            override suspend fun search(page: Int, query: String, filters: FilterList) = popular(page)
            override suspend fun details(entry: SourceItem) = entry
            override suspend fun units(entry: SourceItem) = listOf(
                SourceUnit(url = "ep-1", index = 1, title = "Ep 1"),
                SourceUnit(url = "ep-2", index = 2, title = "Ep 2"),
            )
            override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()
        }
        val registry = object : ExtensionSourceRegistry { override val sources = emptyList<LoadedSource>() }
        val content = ExtensionContentRepository(
            registry,
            persistUnits = { _, units -> persistedUnits += units },
            log = { _, _ -> },
            builtInSources = listOf(source),
        )
        val identifier = KitsuIdentifier(
            client = kitsu,
            write = { enriched ->
                kitsuWrites += enriched
                // Simulate Room: the upsert re-emits via observeById.
                entryFlow.value = enriched
            },
            log = { _, _ -> },
        )
        return MediaDetailViewModel(
            entryId = entry.id,
            libraryRepository = library,
            identifier = identifier,
            contentRepository = content,
        ).also { vm = it }
    }

    private fun createPreview(
        existing: LibraryEntryEntity? = null,
        kitsu: KitsuDataSource = FakeKitsu(),
        mediaType: MediaType = MediaType.ANIME,
    ): MediaDetailViewModel {
        // The proxy receives args with the continuation appended; upsert records its entity.
        val libraryDao = proxy<LibraryDao> { method, args ->
            when (method) {
                "observeById" -> MutableStateFlow<LibraryEntryEntity?>(null)
                "observeUnits" -> MutableStateFlow(emptyList<LibraryUnitEntity>())
                "findById" -> null
                "findBySourceIdentity" -> existing
                "belongsToOtherOwner" -> false
                "upsert" -> { upserts += (args.first() as LibraryEntryEntity); Unit }
                "updateProgress" -> { updateProgressCalls++; Unit }
                "updateUnitProgress" -> { updateUnitProgressCalls++; Unit }
                else -> error("Unexpected DAO call: $method")
            }
        }
        val syncDao = proxy<SyncDao> { method, _ ->
            when (method) {
                "currentSession" -> sessionUserId?.let { SyncSessionEntity(userId = it) }
                "enqueue" -> 1L
                else -> error("Unexpected sync call: $method")
            }
        }
        val context = android.content.ContextWrapper(null)
        val library = LibraryRepository(libraryDao, syncDao, SyncScheduler(context), Dispatchers.Unconfined, Json, requestSync = {})
        val sourceId = when (mediaType) {
            MediaType.MANGA -> "test-manga"
            MediaType.MUSIC -> "test-music"
            MediaType.ANIME -> "test-anime"
        }
        val source = object : MediaSource {
            override val id = sourceId
            override val name = "Test Source"
            override val mediaType = mediaType
            override val baseUrl = "https://example.test"
            override suspend fun configuration() = emptyMap<String, String>()
            override suspend fun updateConfiguration(values: Map<String, String>) = Unit
            override suspend fun popular(page: Int) = SourcePage(emptyList(), false)
            override suspend fun latest(page: Int) = popular(page)
            override suspend fun search(page: Int, query: String, filters: FilterList) = popular(page)
            override suspend fun details(entry: SourceItem) = entry
            override suspend fun units(entry: SourceItem) = listOf(
                SourceUnit(url = "ep-1", index = 1, title = "Ep 1"),
                SourceUnit(url = "ep-2", index = 2, title = "Ep 2"),
            )
            override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()
        }
        val registry = object : ExtensionSourceRegistry { override val sources = emptyList<LoadedSource>() }
        val content = ExtensionContentRepository(
            registry,
            persistUnits = { _, units -> persistedUnits += units },
            log = { _, _ -> },
            builtInSources = listOf(source),
        )
        val identifier = KitsuIdentifier(
            client = kitsu,
            write = { error("Preview must not write Kitsu enrichment") },
            log = { _, _ -> },
        )
        val preview = PreviewDetail(
            item = SourceItem(url = "/anime/frieren", title = "Frieren", thumbnailUrl = "https://img.test/old.jpg"),
            sourceId = sourceId,
            sourcePackageName = "builtin",
            sourceAniyomiId = 0L,
            mediaType = mediaType,
        )
        return MediaDetailViewModel(
            entryId = "",
            libraryRepository = library,
            identifier = identifier,
            contentRepository = content,
            preview = preview,
        ).also { vm = it }
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T

    private class FakeKitsu(
        private val attributes: KitsuAnimeAttributes = KitsuAnimeAttributes(
            canonicalTitle = "Frieren",
            titles = KitsuTitles(en = "Frieren: Beyond Journey's End", jaJp = "フリーレン", enJp = "Frieren"),
            synopsis = "A synopsis.",
            status = "finished",
            subtype = "TV",
            ageRating = "PG",
            averageRating = 87.3,
            startDate = "2023-09-29",
            episodeCount = 28,
            posterImage = KitsuImage(large = "https://img.test/poster.jpeg"),
            coverImage = KitsuImage(large = "https://img.test/cover.jpeg"),
        ),
    ) : KitsuDataSource {

        override suspend fun searchAnime(query: String, limit: Int, offset: Int) =
            Result.success(
                KitsuPage(
                    resources = listOf(KitsuResource(46474L, attributes)),
                    totalCount = 1, limit = limit, offset = offset,
                )
            )

        override suspend fun searchManga(query: String, limit: Int, offset: Int) =
            Result.success(
                KitsuPage<KitsuMangaAttributes>(resources = emptyList(), totalCount = 0, limit = limit, offset = offset)
            )

        override suspend fun anime(id: Long) = Result.failure<KitsuAnimeAttributes>(java.io.IOException("not scripted"))
        override suspend fun manga(id: Long) = Result.failure<KitsuMangaAttributes>(java.io.IOException("not scripted"))
    }
}
