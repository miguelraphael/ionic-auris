package com.auris.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.local.entity.SyncOutboxEntity
import com.auris.app.data.local.entity.SyncSessionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class AccountDataDeletionTest {
    private lateinit var db: AurisDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AurisDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun entry(id: String, owner: String?) = LibraryEntryEntity(
        id = id, userId = owner, mediaType = MediaType.ANIME,
        sourceId = "example.org", sourceKey = id, title = "Private $id",
    )

    private suspend fun enqueue(id: String, owner: String?) {
        db.syncDao().enqueue(SyncOutboxEntity(
            entityId = id, mediaType = MediaType.ANIME,
            payload = Json.encodeToString(LibraryEntryEntity.serializer(), entry(id, owner)),
        ))
    }

    private fun count(table: String, idColumn: String, id: String): Int =
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE $idColumn = ?", arrayOf(id),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Test fun deletingAccountRemovesUnitsAndOwnedOrphansWithoutForeignKeys() = runTest {
        db.syncDao().saveSession(SyncSessionEntity("account-a"))
        val entries = listOf(entry("a", "account-a"), entry("b", "account-b"), entry("legacy", null))
        entries.forEach {
            db.libraryDao().upsert(it)
            db.libraryDao().upsertUnits(listOf(LibraryUnitEntity(entryId = it.id, unitIndex = 1, title = it.title)))
            enqueue(it.id, it.userId)
        }
        enqueue("orphan-a", "account-a")
        enqueue("orphan-b", "account-b")
        enqueue("orphan-legacy", null)
        db.syncDao().enqueue(SyncOutboxEntity(
            entityId = "malformed", mediaType = MediaType.ANIME, payload = "invalid json",
        ))
        // Prove the cleanup is explicit rather than merely testing a working cascade.
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")

        db.syncDao().deleteAccountData("account-a")

        assertEquals(0, count("library_entries", "id", "a"))
        assertEquals(0, count("library_units", "entry_id", "a"))
        assertEquals(0, count("sync_outbox", "entity_id", "a"))
        assertEquals(0, count("sync_outbox", "entity_id", "orphan-a"))
        for (id in listOf("b", "legacy")) {
            assertEquals(1, count("library_entries", "id", id))
            assertEquals(1, count("library_units", "entry_id", id))
            assertEquals(1, count("sync_outbox", "entity_id", id))
        }
        for (id in listOf("orphan-b", "orphan-legacy", "malformed")) {
            assertEquals(1, count("sync_outbox", "entity_id", id))
        }
    }

    @Test fun confirmedTombstonePurgeExplicitlyRemovesOnlyItsUnits() = runTest {
        db.syncDao().saveSession(SyncSessionEntity("account-a"))
        val deleted = entry("deleted", "account-a")
        val live = entry("live", "account-a")
        val other = entry("other", "account-b").copy(isDeleted = true)
        for (row in listOf(deleted, live, other)) {
            db.libraryDao().upsert(row)
            db.libraryDao().upsertUnits(listOf(LibraryUnitEntity(entryId = row.id, unitIndex = 1, title = row.title)))
            enqueue(row.id, row.userId)
        }
        db.libraryDao().softDelete(deleted.id)
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")

        db.syncDao().clearOutboxForEntry(deleted.id)
        assertEquals(1, db.libraryDao().purgeTombstone(deleted.id))
        assertEquals(0, db.libraryDao().purgeTombstone(live.id))
        assertEquals(0, db.libraryDao().purgeTombstone(other.id))

        assertEquals(0, count("library_units", "entry_id", deleted.id))
        assertEquals(0, count("sync_outbox", "entity_id", deleted.id))
        for (id in listOf(live.id, other.id)) {
            assertEquals(1, count("library_units", "entry_id", id))
            assertEquals(1, count("sync_outbox", "entity_id", id))
        }
    }
}
