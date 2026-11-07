package com.auris.app.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.AurisDatabase
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.local.entity.SyncOp
import com.auris.app.data.local.entity.SyncOutboxEntity
import com.auris.app.data.local.entity.SyncSessionEntity
import com.auris.app.data.remote.SupabaseLibraryDataSource
import com.auris.app.data.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercises the worker's pass against real Room and a controlled server boundary. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class LibrarySyncWorkerTest {
    private lateinit var db: AurisDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var remote: FakeRemote
    private lateinit var pass: LibrarySyncPass

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AurisDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = LibraryRepository(db.libraryDao(), db.syncDao(), SyncScheduler(context),
            Dispatchers.Unconfined, Json, requestSync = {})
        remote = FakeRemote()
        pass = LibrarySyncPass(db.libraryDao(), db.syncDao(), repo, remote) { "account-a" }
        kotlinx.coroutines.runBlocking { db.syncDao().saveSession(SyncSessionEntity("account-a")) }
    }

    @After fun close() = db.close()

    private fun entry(key: String = "track") = LibraryEntryEntity(
        userId = "account-a", mediaType = MediaType.MUSIC, sourceId = "innertube",
        sourceKey = key, title = key, syncState = SyncState.SYNCED,
    )

    @Test fun progressCoalescesAndUploadsTheLatestResumeState() = runTest {
        val row = entry()
        db.libraryDao().upsert(row)
        repo.updateProgress(row.id, 0.2f, 2, "Two", 2000, EntryStatus.IN_PROGRESS)
        repo.updateProgress(row.id, 0.7f, 7, "Seven", 7000, EntryStatus.IN_PROGRESS)
        val pending = db.syncDao().nextPendingFor("account-a").single()
        val payload = Json.decodeFromString(LibraryEntryEntity.serializer(), pending.payload!!)
        assertEquals(7000L, payload.lastPositionMs)

        assertEquals(1, pass.push("account-a"))
        assertEquals(7000L, remote.uploaded.single().lastPositionMs)
        assertEquals(7, remote.uploaded.single().currentUnitIndex)
        assertEquals(SyncState.SYNCED, db.libraryDao().findById(row.id)!!.syncState)
        assertTrue(db.syncDao().nextPendingFor("account-a").isEmpty())
    }

    @Test fun legacyPayloadlessProgressAndOlderSnapshotsUseTheCurrentOwnedRow() = runTest {
        val row = entry()
        db.libraryDao().upsert(row)
        db.syncDao().enqueue(SyncOutboxEntity(entityId = row.id, mediaType = row.mediaType,
            syncOp = SyncOp.UPSERT, payload = Json.encodeToString(LibraryEntryEntity.serializer(), row)))
        db.libraryDao().updateProgress(row.id, 0.9f, 9, "Nine", 9000, null)
        db.syncDao().enqueue(SyncOutboxEntity(entityId = row.id, mediaType = row.mediaType,
            syncOp = SyncOp.PUSH_PROGRESS, dedupeKey = "progress"))

        assertEquals(2, pass.push("account-a"))
        assertTrue(remote.uploaded.all { it.lastPositionMs == 9000L })
        assertTrue(db.syncDao().nextPendingFor("account-a").isEmpty())
    }

    @Test fun favoriteWritesQueueAnUpload() = runTest {
        val row = entry()
        db.libraryDao().upsert(row)
        repo.setFavorite(row.id, true)
        assertEquals(1, pass.push("account-a"))
        assertTrue(remote.uploaded.single().isFavorite)
    }

    @Test fun failedDeleteAndPullCannotResurrectTheLocalTombstone() = runTest {
        val row = entry()
        db.libraryDao().upsert(row)
        repo.delete(row.id)
        remote.deleteFailure = TransientSyncException("offline")
        remote.rows = listOf(row.copy(title = "Stale remote", updatedAt = 100, remoteUpdatedAt = 100))

        assertEquals(0, pass.push("account-a"))
        pass.pull("account-a")

        val retained = db.libraryDao().findById(row.id)!!
        assertTrue(retained.isDeleted)
        assertEquals(SyncState.PENDING_UPLOAD, retained.syncState)
        assertEquals(1, db.syncDao().nextPendingFor("account-a").single().attempts)
    }

    @Test fun confirmedDeletePurgesUnitsAndEveryStaleQueuedWriteForOnlyThatEntry() = runTest {
        val row = entry()
        val other = entry("other").copy(userId = "account-b")
        db.libraryDao().upsert(row)
        db.libraryDao().upsert(other)
        db.libraryDao().upsertUnits(listOf(LibraryUnitEntity(entryId = row.id, unitIndex = 1, title = "One")))
        repo.updateProgress(row.id, 0.2f, 1, "One", 2000, null)
        repo.delete(row.id)
        db.syncDao().enqueue(SyncOutboxEntity(entityId = other.id, mediaType = other.mediaType))

        pass.push("account-a")

        assertEquals(listOf(row.remoteId), remote.deleted)
        assertTrue(remote.uploaded.isEmpty())
        assertNull(db.libraryDao().findById(row.id))
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM library_units WHERE entry_id = ?", arrayOf(row.id)).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        assertTrue(db.syncDao().nextPendingFor("account-a").isEmpty())
        assertEquals(1, db.syncDao().nextPendingFor("account-b").size)
    }

    @Test fun failedPushPullPreservesPendingLocalProgress() = runTest {
        val row = entry()
        db.libraryDao().upsert(row)
        repo.updateProgress(row.id, 0.8f, 8, "Eight", 8000, null)
        remote.upsertFailure = TransientSyncException("offline")
        remote.rows = listOf(row.copy(updatedAt = 100, remoteUpdatedAt = 100))

        pass.push("account-a")
        pass.pull("account-a")

        assertEquals(8000L, db.libraryDao().findById(row.id)!!.lastPositionMs)
        assertEquals(SyncState.PENDING_UPLOAD, db.libraryDao().findById(row.id)!!.syncState)
    }

    @Test fun pullWatermarkUsesImportedRemoteTimeAndDoesNotAdvanceAfterFailure() = runTest {
        remote.rows = listOf(entry().copy(updatedAt = 123, remoteUpdatedAt = 123))
        assertEquals(1, pass.pull("account-a"))
        assertEquals(123L, db.syncDao().currentSession()!!.lastPulledAt)
        pass.pull("account-a")
        assertEquals(123L, remote.since)
        remote.fetchFailure = TransientSyncException("page failed")
        try {
            pass.pull("account-a")
            fail("Expected the failed pull to propagate")
        } catch (_: TransientSyncException) { }
        assertEquals(123L, db.syncDao().currentSession()!!.lastPulledAt)
    }

    @Test fun cancellationIsNotRecordedAsARetryableRowFailure() = runTest {
        val row = entry()
        db.libraryDao().upsert(row)
        repo.setFavorite(row.id, true)
        remote.upsertFailure = kotlinx.coroutines.CancellationException("stopped")
        try {
            pass.push("account-a")
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(0, db.syncDao().nextPendingFor("account-a").single().attempts)
    }

    private class FakeRemote : SupabaseLibraryDataSource {
        var rows = emptyList<LibraryEntryEntity>()
        val uploaded = mutableListOf<LibraryEntryEntity>()
        val deleted = mutableListOf<String>()
        var upsertFailure: Exception? = null
        var deleteFailure: Exception? = null
        var fetchFailure: Exception? = null
        var since: Long? = null
        override suspend fun upsertEntry(userId: String, entry: LibraryEntryEntity) {
            upsertFailure?.let { throw it }
            uploaded += entry
        }
        override suspend fun deleteEntry(userId: String, entryId: String) {
            deleteFailure?.let { throw it }
            deleted += entryId
        }
        override suspend fun deleteAllEntries(userId: String) = Unit
        override suspend fun fetchEntries(userId: String, since: Long?): List<LibraryEntryEntity> {
            this.since = since
            fetchFailure?.let { throw it }
            return rows
        }
    }
}
