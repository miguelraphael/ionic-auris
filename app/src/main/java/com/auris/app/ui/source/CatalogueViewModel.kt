package com.auris.app.ui.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.core.model.MediaType
import com.auris.app.source.ExtensionLoader
import com.auris.app.source.MediaSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
        loadJob = viewModelScope.launch {
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
                    // A catalogue failure is usually the source's site, not this screen, so the
                    // message says where it came from rather than "try again".
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CatalogueViewModel(extensionLoader, sourceKey, mediaType) as T
    }

    companion object {
        /** Long enough to avoid a request per keystroke, short enough to feel immediate. */
        const val SEARCH_DEBOUNCE_MS = 350L

        fun factory(
            extensionLoader: ExtensionLoader,
            sourceKey: String,
            mediaType: MediaType = MediaType.ANIME,
        ): ViewModelProvider.Factory = Factory(extensionLoader, sourceKey, mediaType)
    }

    /**
     * Handles a catalogue item click: resolves the source, creates a library entry,
     * upserts it, and navigates to the detail screen.
     */
    fun onItemClick(
        item: com.auris.app.source.SourceItem,
        navController: androidx.navigation.NavController,
        libraryRepository: com.auris.app.data.repository.LibraryRepository,
        extensionContentRepository: com.auris.app.data.repository.ExtensionContentRepository
    ) {
        viewModelScope.launch {
            val sourceKey = _state.value.sourceKey
            val resolved = SourceResolver.resolveLoaded(extensionLoader, sourceKey)
            if (resolved == null) return@launch

            val sourcePackageName = resolved.packageName
            val sourceAniyomiId = resolved.aniyomiId

            val entry = extensionContentRepository.entryForSourceItem(
                sourceItem = item,
                sourcePackageName = sourcePackageName,
                sourceAniyomiId = sourceAniyomiId,
                mediaType = mediaType
            )

            val saved = libraryRepository.upsert(entry)
            navController.navigate(com.auris.app.ui.navigation.Routes.animeDetail(saved.id))
        }
    }
}
