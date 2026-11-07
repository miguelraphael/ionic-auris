package com.auris.app.ui.reader

import androidx.lifecycle.viewModelScope
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.data.sync.SyncScheduler
import com.auris.app.source.*
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
class MangaReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val entry = LibraryEntryEntity(id = "entry", mediaType = MediaType.MANGA,
        sourceId = "test-manga", sourceKey = "manga", title = "Manga")
    private val units = MutableStateFlow(listOf(
        LibraryUnitEntity("entry", 1, "chapter-a", "Chapter A", lastPositionMs = 2),
        LibraryUnitEntity("entry", 2, "chapter-b", "Chapter B"),
    ))
    private var calls = 0
    private var fail = false
    private var lastProgressPage: Long? = null
    private var queuedMediaType: MediaType? = null
    private var vm: MangaReaderViewModel? = null

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T

    private fun reader(index: Int = 1): MangaReaderViewModel {
        val dao = proxy<LibraryDao> { method, args -> when (method) {
            "observeById" -> MutableStateFlow(entry)
            "findById" -> entry
            "observeUnits" -> units
            "updateUnitProgress" -> { lastProgressPage = args[3] as Long; Unit }
            "updateProgress" -> Unit
            else -> error("Unexpected DAO call: $method")
        } }
        val syncDao = proxy<SyncDao> { method, args ->
            if (method == "enqueue") {
                queuedMediaType = (args[0] as com.auris.app.data.local.entity.SyncOutboxEntity).mediaType
                1L
            } else error("Unexpected sync call: $method")
        }
        val context = android.content.ContextWrapper(null)
        val library = LibraryRepository(dao, syncDao, SyncScheduler(context), dispatcher, Json)
        val source = object : MediaSource, MangaReaderSource {
            override val id = "test-manga"
            override val name = "Test Manga"
            override val mediaType = MediaType.MANGA
            override val baseUrl = "https://example.test"
            override suspend fun configuration() = emptyMap<String, String>()
            override suspend fun updateConfiguration(values: Map<String, String>) = Unit
            override suspend fun popular(page: Int) = SourcePage(emptyList(), false)
            override suspend fun latest(page: Int) = popular(page)
            override suspend fun search(page: Int, query: String, filters: FilterList) = popular(page)
            override suspend fun details(entry: SourceItem) = entry
            override suspend fun units(entry: SourceItem) = emptyList<SourceUnit>()
            override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()
            override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
                calls++
                if (fail) throw java.io.IOException("offline")
                return List(4) { Page(it, "https://example.test/$it.jpg") }
            }
        }
        val registry = object : ExtensionSourceRegistry { override val sources = emptyList<LoadedSource>() }
        val content = ExtensionContentRepository(registry, { _, _ -> }, { _, _ -> }, listOf(source))
        return MangaReaderViewModel("entry", index, library, content).also { vm = it }
    }

    private suspend fun TestScope.awaitLoad(reader: MangaReaderViewModel) {
        runCurrent()
        reader.state.first { !it.isLoading }
        advanceUntilIdle()
    }

    @Test fun loadsWithoutWaitingForMetadataCollectionAndRestoresPage() = runTest(dispatcher) {
        val reader = reader()
        awaitLoad(reader)
        assertEquals(1, calls)
        assertFalse(reader.state.value.isLoading)
        assertEquals(4, reader.state.value.totalPages)
        assertEquals(2, reader.state.value.currentPage)
        assertEquals(2L, lastProgressPage)
        assertEquals(MediaType.MANGA, queuedMediaType)
        assertEquals(2, reader.state.value.nextChapter)
        // A Room emission must not scrape the same chapter again.
        units.value = units.value.map { it.copy(title = "Updated") }
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun missingChapterDoesNotOpenAnotherChapter() = runTest(dispatcher) {
        val reader = reader(99)
        awaitLoad(reader)
        assertEquals(0, calls)
        assertFalse(reader.state.value.isLoading)
        assertNotNull(reader.state.value.error)
    }

    @Test fun retriesFailedRequestAndPersistsPageChanges() = runTest(dispatcher) {
        fail = true
        val reader = reader()
        awaitLoad(reader)
        assertNotNull(reader.state.value.error)
        fail = false
        reader.retry()
        awaitLoad(reader)
        assertEquals(2, calls)
        assertNull(reader.state.value.error)
        reader.onPageChanged(3)
        advanceUntilIdle()
        assertEquals(3L, lastProgressPage)
    }
}
