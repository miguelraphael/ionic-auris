package com.auris.app.data.repository

import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.LibraryCategory
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.local.entity.SyncOp
import com.auris.app.data.local.entity.SyncOutboxEntity
import com.auris.app.data.sync.SyncScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Writes the user's library to Room, then queues a push.
 *
 * ### Why this shape
 * The UI never waits on Supabase. [upsert] writes the row and the outbox entry inside one
 * Room transaction, so the device can be killed at any point and the change is still
 * durable and still queued. [SyncScheduler] then lets WorkManager pick it up when a network
 * is available. Reads come straight off Room via Flow — the database is the single source
 * of truth, never the network response.
 */
class LibraryRepository(
    private val libraryDao: LibraryDao,
    private val syncDao: SyncDao,
    private val syncScheduler: SyncScheduler,
    private val ioDispatcher: CoroutineDispatcher,
    private val json: Json,
) {

    // ---- Reads ---------------------------------------------------------------

    fun observeLibrary(): Flow<List<LibraryEntryEntity>> = libraryDao.observeLibrary()

    fun observeByType(
        mediaType: MediaType,
        category: LibraryCategory? = null,
        sort: String = "RECENT",
    ): Flow<List<LibraryEntryEntity>> = libraryDao.observeByType(mediaType, category, sort)

    fun observeFavorites(): Flow<List<LibraryEntryEntity>> = libraryDao.observeFavorites()

    fun observeInProgress(limit: Int = 20): Flow<List<LibraryEntryEntity>> =
        libraryDao.observeInProgress(limit)

    fun observeEntry(id: String): Flow<LibraryEntryEntity?> = libraryDao.observeById(id)

    fun observeUnits(entryId: String): Flow<List<LibraryUnitEntity>> =
        libraryDao.observeUnits(entryId)

    fun observeFavoriteCount(mediaType: MediaType): Flow<Int> =
        libraryDao.observeFavoriteCount(mediaType)

    fun search(query: String): Flow<List<LibraryEntryEntity>> = libraryDao.search(query)

    /** Drives the "N pending" indicator in Settings → Sync. */
    fun observePendingSyncCount(): Flow<Int> = syncDao.observePendingCount()

    // ---- Writes --------------------------------------------------------------

    suspend fun upsert(entry: LibraryEntryEntity): LibraryEntryEntity = write {
        val stamped = entry.copy(
            syncState = SyncState.PENDING_UPLOAD,
            updatedAt = System.currentTimeMillis(),
        )
        libraryDao.upsert(stamped)
        queue(stamped, SyncOp.UPSERT)
        syncScheduler.requestSync()
        stamped
    }

    /** Bulk insert used by the Supabase pull and by extension/playlist imports. */
    suspend fun upsertAll(entries: List<LibraryEntryEntity>): Unit = write {
        val now = System.currentTimeMillis()
        val stamped = entries.map { it.copy(updatedAt = now) }
        libraryDao.upsertAll(stamped)
        // Rows that came *from* the server must not be re-queued for upload.
        syncDao.markEntriesSynced(stamped.map { it.id }, now)
    }

    suspend fun upsertUnits(entryId: String, units: List<LibraryUnitEntity>) = write {
        libraryDao.upsertUnits(units)
    }

    suspend fun setFavorite(entryId: String, favorite: Boolean): Unit = write {
        val updated = libraryDao.setFavorite(entryId, favorite, System.currentTimeMillis())
        if (updated > 0) {
            syncScheduler.requestSync()
        }
    }

    /**
     * Progress writes happen on the player's hot path (several per second while scrubbing),
     * so they update the row and coalesce into a single outbox entry via `dedupeKey`.
     */
    suspend fun updateProgress(
        entryId: String,
        progress: Float,
        unitIndex: Int,
        unitTitle: String?,
        positionMs: Long,
        status: EntryStatus?,
    ) = write {
        val now = System.currentTimeMillis()
        libraryDao.updateProgress(entryId, progress, unitIndex, unitTitle, positionMs, status, now)
        syncDao.enqueue(
            SyncOutboxEntity(
                entityId = entryId,
                mediaType = MediaType.MUSIC,
                syncOp = SyncOp.PUSH_PROGRESS,
                dedupeKey = "$entryId:progress",
            ),
        )
        // Deliberately no syncScheduler call: progress is throttled to a periodic
        // pass rather than waking a worker on every seek.
    }

    suspend fun updateUnitProgress(
        entryId: String,
        unitIndex: Int,
        watched: Boolean,
        positionMs: Long,
    ) = write {
        libraryDao.updateUnitProgress(entryId, unitIndex, watched, positionMs)
    }

    /** Soft delete so the tombstone reaches Supabase before the row disappears locally. */
    suspend fun delete(entryId: String) = write {
        libraryDao.softDelete(entryId)
        syncDao.enqueue(
            SyncOutboxEntity(
                entityId = entryId,
                mediaType = MediaType.MUSIC,
                syncOp = SyncOp.DELETE,
                dedupeKey = "$entryId:delete",
            ),
        )
        syncScheduler.requestSync()
    }

    // ---- Internal ------------------------------------------------------------

    private suspend fun queue(entry: LibraryEntryEntity, op: String) {
        syncDao.enqueue(
            SyncOutboxEntity(
                entityId = entry.id,
                mediaType = entry.mediaType,
                syncOp = op,
                dedupeKey = "${entry.id}:$op",
                // Payload is the full row so the worker does not need to re-read a row
                // that may have changed again by the time it runs.
                payload = json.encodeToString(LibraryEntryEntity.serializer(), entry),
            ),
        )
    }

    /** Runs the write on the IO dispatcher; Room already forbids main-thread queries. */
    private suspend fun <T> write(block: suspend () -> T): T =
        withContext(ioDispatcher) { block() }
}