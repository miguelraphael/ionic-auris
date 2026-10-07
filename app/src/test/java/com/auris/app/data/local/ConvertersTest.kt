package com.auris.app.data.local

import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Enum type-converter tests.
 *
 * ### Why a converter must not throw
 * A converter runs while Room is *reading* a row. An exception from `valueOf` does not fail one
 * query — it propagates out of the observing coroutine and kills the process, so a single
 * unrecognised value makes the app unlaunchable.
 *
 * This is not hypothetical: `EntryStatus.ONGOING` was renamed to `IN_PROGRESS`, rows written by
 * the older build kept the old name, and opening the library crashed on launch with
 * `No enum constant ... ONGOING`. The library was unrecoverable without clearing app data.
 *
 * An enum name in SQLite is an external contract that outlives the code that wrote it — across
 * renames, downgrades and hand-edits. Every case here asserts a fallback rather than a throw.
 */
class ConvertersTest {

    private val converters = Converters()

    // ---- Round trips --------------------------------------------------------

    @Test
    fun roundTripsEveryEnumConstant() {
        MediaType.entries.forEach {
            assertEquals(it, converters.stringToMediaType(converters.mediaTypeToString(it)))
        }
        EntryStatus.entries.forEach {
            assertEquals(it, converters.stringToEntryStatus(converters.entryStatusToString(it)))
        }
        SyncState.entries.forEach {
            assertEquals(it, converters.stringToSyncState(converters.syncStateToString(it)))
        }
    }

    @Test
    fun nullStaysNull() {
        assertNull(converters.stringToMediaType(null))
        assertNull(converters.stringToEntryStatus(null))
        assertNull(converters.stringToSyncState(null))
        assertNull(converters.mediaTypeToString(null))
    }

    // ---- The regression -----------------------------------------------------

    @Test
    fun renamedEntryStatusFallsBackInsteadOfCrashing() {
        // The literal that crashed the app.
        val stale = converters.stringToEntryStatus("ONGOING")

        // A fallback, not an exception. The row's status is wrong; the app is still usable.
        assertEquals(EntryStatus.entries.first(), stale)
    }

    @Test
    fun unknownMediaTypeFallsBackInsteadOfCrashing() {
        assertEquals(MediaType.entries.first(), converters.stringToMediaType("PODCAST"))
    }

    @Test
    fun unknownSyncStateFallsBackInsteadOfCrashing() {
        assertEquals(SyncState.entries.first(), converters.stringToSyncState("HALF_SYNCED"))
    }

    @Test
    fun blankValueIsTreatedAsAbsent() {
        assertNull(converters.stringToEntryStatus(""))
        assertNull(converters.stringToEntryStatus("   "))
    }

    @Test
    fun garbageValueDoesNotThrow() {
        // Whatever a corrupted or hand-edited database contains, reading it must not crash.
        listOf("!!!", "0", "{}", "drop table library_entries", "ENTRY_STATUS").forEach { value ->
            converters.stringToEntryStatus(value)
            converters.stringToMediaType(value)
            converters.stringToSyncState(value)
        }
    }
}