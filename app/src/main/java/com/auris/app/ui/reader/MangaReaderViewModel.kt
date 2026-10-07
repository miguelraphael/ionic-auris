package com.auris.app.ui.reader

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.source.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the reader renders. */
data class MangaReaderUiState(
    val title: String = "",
    val chapterTitle: String = "",
    val pages: List<Page> = emptyList(),
    /** Zero-based index of the visible page. */
    val currentPage: Int = 0,
    val isLoading: Boolean = false,
    /** Set once loading finishes with nothing to show. */
    val isEmpty: Boolean = false,
    val error: String? = null,
    /** Progress overlay visibility; toggled by a tap. */
    val isOverlayVisible: Boolean = false,
) {
    val totalPages: Int get() = pages.size

    /** "3 / 24". */
    val progressLabel: String get() = "${currentPage + 1} / $totalPages"
}

/**
 * Drives the manga reader: resolves the chapter and its page images.
 *
 * ### Pages are never cached here
 * Manga image URLs are frequently signed or session-scoped and expire within minutes, so a
 * cached URL would be worse than no cache — it fails to load on the next open and looks like
 * corruption. Coil's disk cache handles repeat views without involving this class, and Coil
 * keys on the URL, so an expired URL misses the cache cleanly rather than serving a stale
 * image.
 *
 * ### One shot per open
 * Pages are resolved once. Re-resolving on every configuration change would re-scrape the
 * chapter and, on a slow connection, drop the reader back to a spinner while rotating.
 */
class MangaReaderViewModel(
    private val entryId: String,
    private val unitIndex: Int,
    private val libraryRepository: LibraryRepository,
    private val contentRepository: ExtensionContentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MangaReaderUiState(isLoading = true))
    val state: StateFlow<MangaReaderUiState> = _state.asStateFlow()

    /** Survives recomposition; the pager owns its own scroll, so this is only a restore hint. */
    private var pageLoadStarted = false

    init {
        viewModelScope.launch {
            // Entry and chapter metadata together, so the title never disagrees with the pages.
            combine(
                libraryRepository.observeEntry(entryId),
                libraryRepository.observeUnits(entryId),
            ) { entry, units -> entry to units }
                .collect { (entry, units) -> applyMetadata(entry, units) }

            loadPages()
        }
    }

    private fun applyMetadata(entry: LibraryEntryEntity?, units: List<LibraryUnitEntity>) {
        if (entry == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "This chapter is no longer in your library",
            )
            return
        }

        // A reader opened for a unit index that no longer exists falls back to the first
        // chapter rather than showing an empty screen with no way out.
        val chapter = units.firstOrNull { it.unitIndex == unitIndex } ?: units.firstOrNull()

        _state.value = _state.value.copy(
            title = entry.title,
            chapterTitle = chapter?.title.orEmpty(),
        )
    }

    /** Resolves the chapter's page images. Runs once per ViewModel. */
    private suspend fun loadPages() {
        if (pageLoadStarted) return
        pageLoadStarted = true

        val entry = libraryRepository.observeEntry(entryId).first() ?: run {
            _state.value = _state.value.copy(isLoading = false, error = "Chapter not found")
            return
        }

        val units = libraryRepository.observeUnits(entryId).first()
        val chapter = units.firstOrNull { it.unitIndex == unitIndex } ?: units.firstOrNull()

        if (chapter == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                isEmpty = true,
                error = "This chapter has no pages",
            )
            return
        }

        contentRepository.fetchPages(
            entry = entry,
            unit = com.auris.app.source.SourceUnit(
                url = chapter.sourceUnitId.orEmpty(),
                index = chapter.unitIndex,
                title = chapter.title,
            ),
        )
            .onSuccess { pages ->
                _state.value = _state.value.copy(
                    pages = pages,
                    isLoading = false,
                    // Distinguishing "empty" from "loaded nothing" matters: the first needs a
                    // spinner, the second needs an explanation.
                    isEmpty = pages.isEmpty(),
                    error = if (pages.isEmpty()) "This chapter has no readable pages" else null,
                )
            }
            .onFailure { error ->
                Log.w(TAG, "Page load failed for $entryId/$unitIndex: ${error.message}")
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = if (error is ExtensionContentRepository.UnavailableException) {
                        error.message
                    } else {
                        "Could not load this chapter. Check your connection and try again."
                    },
                )
            }
    }

    fun onPageChanged(index: Int) {
        if (index == _state.value.currentPage) return
        _state.value = _state.value.copy(currentPage = index)
    }

    /** Tapping the page surface shows or hides the progress overlay. */
    fun toggleOverlay() {
        _state.value = _state.value.copy(isOverlayVisible = !_state.value.isOverlayVisible)
    }

    fun retry() {
        pageLoadStarted = false
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null, isEmpty = false)
            loadPages()
        }
    }

    private class Factory(
        private val entryId: String,
        private val unitIndex: Int,
        private val libraryRepository: LibraryRepository,
        private val contentRepository: ExtensionContentRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MangaReaderViewModel(entryId, unitIndex, libraryRepository, contentRepository) as T
    }

    companion object {
        private const val TAG = "AurisReader"

        fun factory(
            entryId: String,
            unitIndex: Int,
            libraryRepository: LibraryRepository,
            contentRepository: ExtensionContentRepository,
        ): ViewModelProvider.Factory =
            Factory(entryId, unitIndex, libraryRepository, contentRepository)
    }
}