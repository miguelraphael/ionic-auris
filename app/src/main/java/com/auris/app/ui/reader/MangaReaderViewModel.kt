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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import com.auris.app.core.model.EntryStatus

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
    val previousChapter: Int? = null,
    val nextChapter: Int? = null,
) {
    val totalPages: Int get() = pages.size

    /** "3 / 24". */
    val progressLabel: String get() = if (totalPages == 0) "0 / 0" else "${currentPage + 1} / $totalPages"
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
    private var pageLoadJob: Job? = null

    init {
        viewModelScope.launch {
            // Entry and chapter metadata together, so the title never disagrees with the pages.
            combine(
                libraryRepository.observeEntry(entryId),
                libraryRepository.observeUnits(entryId),
            ) { entry, units -> entry to units }
                .collect { (entry, units) -> applyMetadata(entry, units) }
        }
        pageLoadJob = viewModelScope.launch { loadPages() }
    }

    private fun applyMetadata(entry: LibraryEntryEntity?, units: List<LibraryUnitEntity>) {
        if (entry == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = "This chapter is no longer in your library",
            )
            return
        }

        // Never silently substitute a different chapter for a stale saved route.
        val chapter = units.firstOrNull { it.unitIndex == unitIndex }
        val position = chapter?.let { units.indexOf(it) } ?: -1

        _state.value = _state.value.copy(
            title = entry.title,
            chapterTitle = chapter?.title.orEmpty(),
            previousChapter = position.takeIf { it > 0 }?.let { units[it - 1].unitIndex },
            nextChapter = position.takeIf { it >= 0 && it < units.lastIndex }?.let { units[it + 1].unitIndex },
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
        val chapter = units.firstOrNull { it.unitIndex == unitIndex }

        if (chapter == null) {
            _state.value = _state.value.copy(
                isLoading = false,
                isEmpty = true,
                error = "This chapter is no longer available. Go back and refresh the chapter list.",
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
                val restoredPage = chapter.lastPositionMs.toInt().coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                _state.value = _state.value.copy(
                    pages = pages,
                    currentPage = restoredPage,
                    isLoading = false,
                    // Distinguishing "empty" from "loaded nothing" matters: the first needs a
                    // spinner, the second needs an explanation.
                    isEmpty = pages.isEmpty(),
                    error = if (pages.isEmpty()) "This chapter has no readable pages" else null,
                )
                if (pages.isNotEmpty()) saveProgress(restoredPage)
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
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
        if (_state.value.isLoading || index !in _state.value.pages.indices) return
        if (index == _state.value.currentPage) return
        _state.value = _state.value.copy(currentPage = index)
        saveProgress(index)
    }

    private var progressJob: Job? = null

    private fun saveProgress(index: Int) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            val units = libraryRepository.observeUnits(entryId).first()
            val chapter = units.firstOrNull { it.unitIndex == unitIndex } ?: return@launch
            val complete = index == _state.value.totalPages - 1
            // Manga uses lastPositionMs as a zero-based page index; audio/video keep milliseconds.
            libraryRepository.updateUnitProgress(entryId, unitIndex, chapter.isWatched || complete, index.toLong())
            val fraction = (index + 1f) / _state.value.totalPages.coerceAtLeast(1)
            val ordinal = units.indexOfFirst { it.unitIndex == unitIndex }.coerceAtLeast(0)
            libraryRepository.updateProgress(entryId, (ordinal + fraction) / units.size.coerceAtLeast(1),
                unitIndex, chapter.title, index.toLong(),
                if (complete && ordinal == units.lastIndex) EntryStatus.COMPLETED else EntryStatus.IN_PROGRESS,
                mediaType = com.auris.app.core.model.MediaType.MANGA)
        }
    }

    /** Tapping the page surface shows or hides the progress overlay. */
    fun toggleOverlay() {
        _state.value = _state.value.copy(isOverlayVisible = !_state.value.isOverlayVisible)
    }

    fun retry() {
        pageLoadJob?.cancel()
        pageLoadStarted = false
        pageLoadJob = viewModelScope.launch {
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
