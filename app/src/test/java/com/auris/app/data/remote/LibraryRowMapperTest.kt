package com.auris.app.data.remote

import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for the Supabase row: types must match the Postgres columns,
 * not the Room ones.
 *
 * Two real failure modes live here. Epoch-millis numbers into `timestamptz`
 * columns are a 400 on every upsert, and a local-only key the server table
 * lacks (`local_artwork_path`) fails the write the same way — both silent
 * until the first real sync, because the worker early-returns with no session.
 */
class LibraryRowMapperTest {

    private fun entry() = LibraryEntryEntity(
        id = "entry-1",
        remoteId = "entry-1",
        mediaType = MediaType.ANIME,
        sourceId = "1",
        sourceKey = "/a",
        title = "Test",
        localArtworkPath = "/local/art.jpg",
        createdAt = 1_700_000_000_000L,
    )

    @Test
    fun timestampsCrossAsIsoStrings() {
        val row = entry().toRow("user-1", 1_700_000_000_000L)

        assertEquals("2023-11-14T22:13:20Z", row.createdAt)
        assertEquals("2023-11-14T22:13:20Z", row.updatedAt)
    }

    @Test
    fun roundTripPreservesIdentityAndTimes() {
        val entity = entry().toRow("user-1", 1_700_000_000_000L).toEntity()

        assertEquals("entry-1", entity.id)
        assertEquals("entry-1", entity.remoteId)
        assertEquals("user-1", entity.userId)
        assertEquals(MediaType.ANIME, entity.mediaType)
        assertEquals(1_700_000_000_000L, entity.createdAt)
        assertEquals(1_700_000_000_000L, entity.updatedAt)
    }

    @Test
    fun localArtworkPathNeverLeavesTheDevice() {
        val row = entry().toRow("user-1", 1_700_000_000_000L)

        // No such field on the DTO: the server table has no such column.
        assertTrue(
            LibraryEntryRow::class.members.none { it.name == "localArtworkPath" },
        )
        assertNull(row.toEntity().localArtworkPath)
    }

    @Test
    fun unparseableTimestampsFallBackToNow() {
        val row = entry().toRow("user-1", 1_700_000_000_000L)
            .copy(createdAt = "not-a-time", updatedAt = null)

        val before = System.currentTimeMillis()
        val entity = row.toEntity()

        assertTrue(entity.createdAt >= before)
        assertTrue(entity.updatedAt >= before)
    }
}
