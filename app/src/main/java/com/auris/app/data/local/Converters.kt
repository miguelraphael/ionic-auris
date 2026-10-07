package com.auris.app.data.local

import androidx.room.TypeConverter
import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.LibraryCategory
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState

/**
 * Enums and lists are stored as their `name`/`toString` forms rather than ordinals, so
 * reordering the enum in Kotlin can never silently corrupt existing databases.
 */
class Converters {
    @TypeConverter
    fun mediaTypeToString(value: MediaType?): String? = value?.name

    @TypeConverter
    fun stringToMediaType(value: String?): MediaType? = value?.let { MediaType.valueOf(it) }

    @TypeConverter
    fun entryStatusToString(value: EntryStatus?): String? = value?.name

    @TypeConverter
    fun stringToEntryStatus(value: String?): EntryStatus? = value?.let { EntryStatus.valueOf(it) }

    @TypeConverter
    fun syncStateToString(value: SyncState?): String? = value?.name

    @TypeConverter
    fun stringToSyncState(value: String?): SyncState? = value?.let { SyncState.valueOf(it) }

    @TypeConverter
    fun toStringList(value: List<String>?): String = value.orEmpty().joinToString(SEPARATOR)

    @TypeConverter
    fun fromStringList(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else value.split(SEPARATOR)

    @TypeConverter
    fun toCategoryList(value: List<LibraryCategory>?): String =
        value.orEmpty().joinToString(SEPARATOR) { it.name }

    @TypeConverter
    fun fromCategoryList(value: String?): List<LibraryCategory> =
        if (value.isNullOrEmpty()) emptyList()
        else value.split(SEPARATOR).mapNotNull {
            runCatching { LibraryCategory.valueOf(it) }.getOrNull()
        }

    companion object {
        // ASCII unit separator: cannot appear in genre names or enum names.
        const val SEPARATOR = "\u001F"

        /** Bound into LIKE queries that must anchor on a real delimiter. */
        const val CATEGORY_SEPARATOR = SEPARATOR
    }
}