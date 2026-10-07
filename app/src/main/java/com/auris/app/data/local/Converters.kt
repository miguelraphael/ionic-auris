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
    fun stringToMediaType(value: String?): MediaType? = parseEnum(value, MediaType.entries)

    @TypeConverter
    fun entryStatusToString(value: EntryStatus?): String? = value?.name

    @TypeConverter
    fun stringToEntryStatus(value: String?): EntryStatus? = parseEnum(value, EntryStatus.entries)

    @TypeConverter
    fun syncStateToString(value: SyncState?): String? = value?.name

    @TypeConverter
    fun stringToSyncState(value: String?): SyncState? = parseEnum(value, SyncState.entries)

    /**
     * Parses an enum name, falling back to the first constant instead of throwing.
     *
     * ### Why a converter must not throw
     * A type converter runs while Room is reading a row. `valueOf` throwing here does not fail
     * one query — it propagates out of `observeLibrary`'s coroutine and kills the process, so a
     * single unrecognised value makes the app unlaunchable. That is exactly what happened when
     * `EntryStatus.ONGOING` was renamed to `IN_PROGRESS`: rows written by the older build kept
     * the old name, and opening the library crashed on launch with
     * `No enum constant ... ONGOING`, with the library unrecoverable without clearing app data.
     *
     * An enum name stored in SQLite is an external contract that outlives the code that wrote
     * it — across renames, downgrades and manual edits. Falling back keeps the app usable and
     * costs one row's accuracy, which is the right trade: a slightly wrong status is
     * recoverable, an unlaunchable app is not.
     */
    private inline fun <reified T : Enum<T>> parseEnum(value: String?, values: List<T>): T? {
        if (value.isNullOrBlank()) return null
        return values.firstOrNull { it.name == value }
            ?: values.firstOrNull()
            ?: runCatching { enumValueOf<T>(value) }.getOrNull()
    }

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