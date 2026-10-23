package com.auris.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.repository.LibraryRepository
import kotlinx.coroutines.launch

/**
 * The one write the Library screen makes: removing saved titles.
 *
 * Reads deliberately stay in the shell's single `observeLibrary()` snapshot. Deleting through
 * here re-emits from Room, so the grid and Home both lose the row without this class mirroring a
 * second copy of the library.
 *
 * Each delete goes through [LibraryRepository.delete], which soft-deletes locally and enqueues
 * the tombstone. That is why this is not a DAO call: a hard delete here would resurrect on the
 * next sync.
 */
class LibraryViewModel(private val repository: LibraryRepository) : ViewModel() {

    /**
     * Removes [entries], one at a time.
     *
     * Sequential rather than parallel because each call opens a Room write transaction and
     * enqueues an outbox row; a burst of concurrent writers buys nothing for a handful of taps
     * and makes a partial failure harder to reason about. A failure on one row must not stop the
     * rest, so each is caught.
     */
    fun delete(entries: List<LibraryEntryEntity>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            entries.forEach { entry ->
                runCatching { repository.delete(entry.id) }
            }
        }
    }

    class Factory(private val repository: LibraryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository) as T
    }

    companion object {
        fun factory(repository: LibraryRepository): ViewModelProvider.Factory =
            Factory(repository)
    }
}
