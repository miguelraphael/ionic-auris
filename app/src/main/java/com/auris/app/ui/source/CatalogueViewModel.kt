package com.auris.app.ui.source

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.core.model.MediaType
import com.auris.app.source.ExtensionSourceRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.auris.app.source.ExtensionLoader
import com.auris.app.source.MediaSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.auris.app.data.remote.manga.MangaDexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives a single source's catalogue.
 *
 * ### Why the resolver result is held, not re-resolved
 * A source is a live object over a `DexClassLoader`. Resolving it once per ViewModel keeps the
 * catalogue stable across recompositions, and a source that disappears mid-session (disabled,
 * uninstalled) is reported as unavailable rather than throwing from a rendering frame.
 */
class CatalogueViewModel(
    private val extensionLoader: ExtensionSourceRegistry,
    private var sourceKey: String,
    private val mediaType: MediaType,
    private val extensionContentRepository: ExtensionContentRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val initialKey = extensionContentRepository.builtInSources.firstOrNull {
        it.id == sourceKey || "builtin-${it.id}" == sourceKey
    }?.let { "builtin-${it.id}" } ?: sourceKey

    private val _state = MutableStateFlow(
        CatalogueUiState(
            sourceKey = initialKey,
            sourceLanguage = "",
            mediaType = mediaType,
            sources = extensionContentRepository.builtInSources.filter { it.mediaType == mediaType }.map {
                BrowsableSource("builtin-${it.id}", it.name, "", "builtin", it.mediaType)
            },
        ),
    )
    val state: StateFlow<CatalogueUiState> = _state.asStateFlow()

    private var source: MediaSource? = null
    private var loadJob: Job? = null

    init {
        sourceKey = initialKey
        resolveAndLoad()
    }

    private fun resolveAndLoad() {
        viewModelScope.launch {
            val resolved = extensionContentRepository.builtInSources.firstOrNull {
                "builtin-${it.id}" == sourceKey || it.id == sourceKey
            } ?: SourceResolver.resolve(extensionLoader, sourceKey)

            if (resolved == null) {
                _state.value = _state.value.copy(
                    isSourceAvailable = false,
                    isLoading = false,
                    error = "This source is not installed or is disabled",
                )
                return@launch
            }

            source = resolved
            _state.value = _state.value.copy(
                isSourceAvailable = true,
                sourceName = resolved.name,
                mediaType = resolved.mediaType,
                popularLabel = if (resolved.id == "mangapill") "Trending" else "Popular",
                sourceLanguage = when (resolved.id) {
                    MangaDexSource.ID -> "English chapters · MangaDex"
                    com.auris.app.data.remote.manga.WeebCentralSource.ID -> "Official digital scans & volume rips · Weeb Central"
                    com.auris.app.data.remote.anime.AllAnimeSource.ID -> "Sub / Dub · Comprehensive anime library & HLS streams"
                    com.auris.app.data.remote.manga.AllMangaSource.ID -> "Comprehensive manga library · AllManga"
                    "mangapill" -> "English manga · MangaPill"
                    com.auris.app.data.remote.manga.AsuraScansSource.ID -> "English manhwa & comics · Asura Scans"
                    else -> ""
                },
                error = null,
            )
            show(if (_state.value.query.isBlank()) CatalogueUiState.Mode.POPULAR else CatalogueUiState.Mode.SEARCH)
        }
    }

    fun selectSource(key: String) {
        val targetKey = extensionContentRepository.builtInSources.firstOrNull {
            it.id == key || "builtin-${it.id}" == key
        }?.let { "builtin-${it.id}" } ?: key
        if (targetKey == sourceKey || _state.value.sources.none { it.key == targetKey }) return
        loadJob?.cancel()
        sourceKey = targetKey
        source = null
        currentPage = 1
        _state.value = _state.value.copy(
            sourceKey = targetKey,
            items = emptyList(),
            hasNextPage = false,
            isLoading = true,
            error = null,
        )
        resolveAndLoad()
    }

    /** Switches between Popular and Latest. */
    fun selectMode(mode: CatalogueUiState.Mode) {
        if (_state.value.mode == mode && mode != CatalogueUiState.Mode.SEARCH) return
        _state.value = _state.value.copy(mode = mode)
        show(mode)
    }

    /** Search is debounced, so typing does not fire a request per keystroke. */
    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query, mode = CatalogueUiState.Mode.SEARCH)

        loadJob?.cancel()
        if (query.isBlank()) {
            show(CatalogueUiState.Mode.POPULAR)
            return
        }

        show(CatalogueUiState.Mode.SEARCH, debounce = true)
    }

    fun retry() = show(_state.value.mode)

    private var currentPage = 1

    fun loadMore() {
        if (!_state.value.isLoading && _state.value.hasNextPage) show(_state.value.mode, append = true)
    }

    private fun show(mode: CatalogueUiState.Mode, debounce: Boolean = false, append: Boolean = false) {
        val current = source ?: return
        val query = _state.value.query
        val pageNumber = if (append) currentPage + 1 else 1

        loadJob?.cancel()
        // IO, not Main: catalogue fetches block (RxJava `toBlocking`, extension
        // `runBlocking`), and some site interceptors need the main thread for
        // challenge solving — parking Main here deadlocks those sources forever
        // (verified: main parked in `awaitSingle` with no return). StateFlow
        // emissions are thread-safe, so no context switch is needed to publish.
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, error = null, mode = mode,
                items = if (append) _state.value.items else emptyList())
            if (debounce) delay(SEARCH_DEBOUNCE_MS)

            val result = runCatching {
                when (mode) {
                    CatalogueUiState.Mode.POPULAR -> current.popular(pageNumber)
                    CatalogueUiState.Mode.LATEST -> current.latest(pageNumber)
                    CatalogueUiState.Mode.SEARCH -> current.search(pageNumber, query, FilterList())
                }
            }

            currentCoroutineContext().ensureActive()
            result
                .onSuccess { page ->
                    currentPage = pageNumber
                    _state.value = _state.value.copy(
                        items = (if (append) _state.value.items + page.items else page.items).distinctBy { it.url },
                        isLoading = false,
                        hasNextPage = page.hasNextPage,
                        error = null,
                    )
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = error.message?.takeIf { it.isNotBlank() }
                            ?: "The source could not be reached",
                    )
                }
        }
    }

    class Factory(
        private val extensionLoader: ExtensionLoader,
        private val sourceKey: String,
        private val mediaType: MediaType,
        private val extensionContentRepository: ExtensionContentRepository,
        private val libraryRepository: LibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CatalogueViewModel(extensionLoader, sourceKey, mediaType, extensionContentRepository, libraryRepository) as T
    }

    companion object {
        /** Long enough to avoid a request per keystroke, short enough to feel immediate. */
        const val SEARCH_DEBOUNCE_MS = 350L
        private const val TAG = "AurisCatalogue"

        fun factory(
            extensionLoader: ExtensionLoader,
            sourceKey: String,
            mediaType: MediaType = MediaType.ANIME,
            extensionContentRepository: ExtensionContentRepository,
            libraryRepository: LibraryRepository,
        ): ViewModelProvider.Factory = Factory(extensionLoader, sourceKey, mediaType, extensionContentRepository, libraryRepository)
    }

    /**
     * One-shot navigation event carrying the persisted entry id.
     *
     * A SharedFlow with a buffer (not a Channel, not a StateFlow): the event must
     * survive until the UI collects it, must not replay to a new collector, and
     * must never hold a reference to navigation itself — the ViewModel owns the
     * upsert, the composable owns the `navController.navigate()` call.
     */
    private val _openDetail = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openDetail: SharedFlow<String> = _openDetail.asSharedFlow()

    /**
     * Persists the tapped catalogue title and emits its entry id.
     *
     * Non-suspend on purpose: the UI calls this as a plain callback and the
     * coroutine stays inside `viewModelScope`, so no suspend lambda ever crosses
     * the Compose boundary.
     */
    fun onItemClick(item: com.auris.app.source.SourceItem) {
        viewModelScope.launch {
            val builtIn = source?.takeIf { candidate -> extensionContentRepository.builtInSources.any { it.id == candidate.id } }
            val loaded = SourceResolver.resolveLoaded(extensionLoader, _state.value.sourceKey)
            if (builtIn == null && loaded == null) return@launch
            val candidate = extensionContentRepository.entryForSourceItem(
                sourceItem = item,
                sourcePackageName = loaded?.packageName ?: "builtin",
                sourceAniyomiId = loaded?.aniyomiId ?: 0L,
                mediaType = builtIn?.mediaType ?: mediaType,
            ).let { if (builtIn != null) it.copy(sourceId = builtIn.id) else it }
            val saved = runCatching {
                val existing = libraryRepository.findBySource(
                    mediaType = candidate.mediaType,
                    sourceId = candidate.sourceId,
                    sourceKey = candidate.sourceKey,
                )
                if (existing != null) {
                    libraryRepository.upsert(
                        existing.copy(
                            title = candidate.title,
                            author = candidate.author,
                            subtitle = candidate.subtitle,
                            artworkUrl = candidate.artworkUrl ?: existing.artworkUrl,
                            releaseYear = candidate.releaseYear ?: existing.releaseYear,
                        )
                    )
                } else {
                    libraryRepository.upsert(candidate)
                }
            }.onFailure {
                Log.w(TAG, "Catalogue tap upsert failed for ${candidate.sourceKey}", it)
            }.getOrNull() ?: return@launch
            _openDetail.emit(saved.id)
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}
