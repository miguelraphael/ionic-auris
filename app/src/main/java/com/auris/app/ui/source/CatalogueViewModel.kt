package com.auris.app.ui.source

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.core.model.MediaType
import com.auris.app.source.ExtensionLoader
import com.auris.app.source.MediaSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Job
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
    private val extensionLoader: ExtensionLoader,
    private val sourceKey: String,
    private val mediaType: MediaType,
    private val extensionContentRepository: ExtensionContentRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(
        CatalogueUiState(sourceKey = sourceKey, sourceLanguage = ""),
    )
    val state: StateFlow<CatalogueUiState> = _state.asStateFlow()

    private var source: MediaSource? = null
    private var loadJob: Job? = null

    init {
        resolveAndLoad()
    }

    private fun resolveAndLoad() {
        viewModelScope.launch {
            val resolved = SourceResolver.resolve(extensionLoader, sourceKey)

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
                sourceName = SourceResolver.nameOf(extensionLoader, sourceKey).orEmpty(),
                error = null,
            )
            show(CatalogueUiState.Mode.POPULAR)
        }
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

        loadJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            show(CatalogueUiState.Mode.SEARCH)
        }
    }

    fun retry() = show(_state.value.mode)

    private fun show(mode: CatalogueUiState.Mode) {
        val current = source ?: return

        loadJob?.cancel()
        // IO, not Main: catalogue fetches block (RxJava `toBlocking`, extension
        // `runBlocking`), and some site interceptors need the main thread for
        // challenge solving — parking Main here deadlocks those sources forever
        // (verified: main parked in `awaitSingle` with no return). StateFlow
        // emissions are thread-safe, so no context switch is needed to publish.
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(isLoading = true, error = null)

            val result = runCatching {
                when (mode) {
                    CatalogueUiState.Mode.POPULAR -> current.popular(1)
                    CatalogueUiState.Mode.LATEST -> current.latest(1)
                    CatalogueUiState.Mode.SEARCH -> current.search(1, _state.value.query, FilterList())
                }
            }

            result
                .onSuccess { page ->
                    _state.value = _state.value.copy(
                        items = page.items,
                        isLoading = false,
                        hasNextPage = page.hasNextPage,
                        error = null,
                    )
                }
                .onFailure { error ->
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
            val loaded = SourceResolver.resolveLoaded(extensionLoader, _state.value.sourceKey)
                ?: return@launch
            val candidate = extensionContentRepository.entryForSourceItem(
                sourceItem = item,
                sourcePackageName = loaded.packageName,
                sourceAniyomiId = loaded.aniyomiId,
                mediaType = mediaType,
            )
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