package com.auris.app.ui.source

import androidx.lifecycle.viewModelScope
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.data.sync.SyncScheduler
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueSourceSwitchTest {
    private var vm: CatalogueViewModel? = null
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun teardown() { vm?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    @Test fun preservesSearchAndRejectsUnknownSource() = runBlocking {
        val model = create(listOf(TestSource("a"), TestSource("b")))
        await(model) { !it.isLoading && it.items.isNotEmpty() }
        model.onQueryChange("Naruto")
        await(model) { !it.isLoading && it.items.firstOrNull()?.title == "a Naruto" }
        model.selectSource("builtin-b")
        val state = await(model) { !it.isLoading && it.items.firstOrNull()?.title == "b Naruto" }
        assertEquals("Naruto", state.query)
        assertEquals("builtin-b", state.sourceKey)
        model.selectSource("not-installed")
        assertEquals("builtin-b", model.state.value.sourceKey)
    }

    @Test fun slowOldSourceCannotOverwriteNewSource() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val slow = object : TestSource("a") {
            override suspend fun popular(page: Int): SourcePage {
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
                finished.complete(Unit)
                return SourcePage(listOf(SourceItem("old", "Old source", null)), false)
            }
        }
        try {
            val model = create(listOf(slow, TestSource("b")))
            withTimeout(5000) { entered.await() }
            model.selectSource("builtin-b")
            await(model) { !it.isLoading && it.items.firstOrNull()?.title == "b popular" }
            gate.complete(Unit)
            withTimeout(5000) { finished.await() }
            delay(50)
            assertEquals("b popular", model.state.value.items.single().title)
        } finally { gate.complete(Unit) }
    }

    @Test fun initializesWithBareSourceKeyAndAllowsSwitching() = runBlocking {
        val weebCentral = TestSource("weebcentral")
        val mangadex = TestSource("mangadex")
        val asura = TestSource("asurascans")
        val allmanga = TestSource("allmanga")
        val model = create(listOf(weebCentral, mangadex, asura, allmanga), initialKey = "weebcentral")
        val state = await(model) { !it.isLoading && it.isSourceAvailable }
        assertEquals("builtin-weebcentral", state.sourceKey)
        assertEquals("weebcentral", state.sourceName)

        // Switching to mangadex works
        model.selectSource("builtin-mangadex")
        val stateDex = await(model) { !it.isLoading && it.sourceKey == "builtin-mangadex" }
        assertEquals("mangadex", stateDex.sourceName)

        // Switching to asura works
        model.selectSource("builtin-asurascans")
        val stateAsura = await(model) { !it.isLoading && it.sourceKey == "builtin-asurascans" }
        assertEquals("asurascans", stateAsura.sourceName)

        // Switching to allmanga works
        model.selectSource("builtin-allmanga")
        val stateAll = await(model) { !it.isLoading && it.sourceKey == "builtin-allmanga" }
        assertEquals("allmanga", stateAll.sourceName)
    }

    private fun create(sources: List<MediaSource>, initialKey: String = "builtin-a"): CatalogueViewModel {
        val registry = object : ExtensionSourceRegistry { override val sources = emptyList<LoadedSource>() }
        val library = LibraryRepository(proxy<LibraryDao>(), proxy<SyncDao>(),
            SyncScheduler(android.content.ContextWrapper(null)), Dispatchers.IO, Json)
        val content = ExtensionContentRepository(registry, { _, _ -> }, builtInSources = sources)
        return CatalogueViewModel(registry, initialKey, MediaType.MANGA, content, library).also { vm = it }
    }

    private suspend fun await(model: CatalogueViewModel, predicate: (CatalogueUiState) -> Boolean) =
        withContext(Dispatchers.Default) { withTimeout(5000) { model.state.first(predicate) } }

    private inline fun <reified T> proxy(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected database operation: ${method.name}") } as T

    private open class TestSource(override val id: String) : MediaSource {
        override val name = id
        override val mediaType = MediaType.MANGA
        override val baseUrl = "https://example.test"
        override suspend fun configuration() = emptyMap<String, String>()
        override suspend fun updateConfiguration(values: Map<String, String>) = Unit
        override suspend fun popular(page: Int) = SourcePage(listOf(SourceItem(id, "$id popular", null)), false)
        override suspend fun latest(page: Int) = popular(page)
        override suspend fun search(page: Int, query: String, filters: FilterList) = SourcePage(listOf(SourceItem(id, "$id $query", null)), false)
        override suspend fun details(entry: SourceItem) = entry
        override suspend fun units(entry: SourceItem) = emptyList<SourceUnit>()
        override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()
    }
}
