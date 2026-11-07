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
import kotlinx.coroutines.sync.withLock
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
    private val requestSync: () -> Unit = syncScheduler::requestSync,
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

    /**
     * Finds the existing row for a catalogue title, if any.
     *
     * `@Upsert` keys on the primary key `id`, not on the unique index
     * (user_id, media_type, source_id, source_key), so use the active owner's exact
     * source identity when finding an existing row.
     */
    suspend fun findBySource(
        mediaType: MediaType,
        sourceId: String,
        sourceKey: String,
    ): LibraryEntryEntity? = withContext(ioDispatcher) {
        libraryDao.findBySourceIdentity(mediaType, sourceId, sourceKey)
    }

    fun observeUnits(entryId: String): Flow<List<LibraryUnitEntity>> =
        libraryDao.observeUnits(entryId)

    fun observeFavoriteCount(mediaType: MediaType): Flow<Int> =
        libraryDao.observeFavoriteCount(mediaType)

    fun search(query: String): Flow<List<LibraryEntryEntity>> = libraryDao.search(query)

    /** Drives the "N pending" indicator in Settings → Sync. */
    fun observePendingSyncCount(): Flow<Int> = syncDao.observePendingCount()

    // ---- Writes --------------------------------------------------------------

    suspend fun upsert(entry: LibraryEntryEntity): LibraryEntryEntity = write {
        val owner = syncDao.currentSession()?.userId ?: error("Not signed in")
        require(entry.userId == null || entry.userId == owner) { "Entry belongs to another account" }
        val existing = libraryDao.findBySourceIdentity(entry.mediaType, entry.sourceId, entry.sourceKey)
        // Music mappers use device-wide deterministic ids. Namespace new source identities
        // per account so saving the same title cannot replace another account's primary key.
        val id = existing?.id ?: if (entry.userId == null) java.util.UUID.nameUUIDFromBytes(
            listOf(owner, entry.mediaType.name, entry.sourceId, entry.sourceKey)
                .joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        ).toString() else entry.id
        require(!libraryDao.belongsToOtherOwner(id, owner)) { "Entry belongs to another account" }
        val stamped = entry.copy(
            id = id,
            remoteId = existing?.remoteId ?: if (entry.userId == null) id else entry.remoteId,
            userId = owner,
            syncState = SyncState.PENDING_UPLOAD,
            updatedAt = System.currentTimeMillis(),
        )
        libraryDao.upsert(stamped)
        queue(stamped, SyncOp.UPSERT)
        requestSync()
        stamped
    }

    /** Bulk insert used by the Supabase pull and by extension/playlist imports. */
    suspend fun upsertAll(entries: List<LibraryEntryEntity>): Unit = write {
        val owner = syncDao.currentSession()?.userId ?: return@write
        mergeRemoteEntries(owner, entries)
    }

    /** Caller must hold AccountAccess.mutex, including the worker's complete network pass. */
    internal suspend fun mergeRemoteEntries(owner: String, entries: List<LibraryEntryEntity>) {
        require(entries.all { it.userId == owner }) { "Cannot import another account's entries" }
        val mapped = entries.mapNotNull { entry ->
            val existing = libraryDao.findBySourceIdentity(entry.mediaType, entry.sourceId, entry.sourceKey)
            // Never let a stale pull undo a pending local write, especially a delete.
            if (existing != null && (existing.isDeleted || existing.syncState != SyncState.SYNCED)) {
                return@mapNotNull null
            }
            val localId = existing?.id ?: if (libraryDao.belongsToOtherOwner(entry.id, owner)) {
                java.util.UUID.nameUUIDFromBytes("$owner:remote:${entry.remoteId}".toByteArray(Charsets.UTF_8)).toString()
            } else entry.id
            require(!libraryDao.belongsToOtherOwner(localId, owner)) { "Conflicting account identity" }
            entry.copy(id = localId)
        }
        libraryDao.upsertAll(mapped)
        syncDao.markEntriesSynced(mapped.map { it.id }, System.currentTimeMillis())
    }

    suspend fun upsertUnits(entryId: String, units: List<LibraryUnitEntity>) = write {
        if (libraryDao.findById(entryId) == null) return@write
        require(units.all { it.entryId == entryId }) { "Units belong to another entry" }
        libraryDao.upsertUnits(units)
    }

    suspend fun setFavorite(entryId: String, favorite: Boolean): Unit = write {
        val updated = libraryDao.setFavorite(entryId, favorite, System.currentTimeMillis())
        if (updated > 0) {
            libraryDao.findById(entryId)?.let { queue(it, SyncOp.UPSERT) }
            requestSync()
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
        mediaType: MediaType = MediaType.MUSIC,
    ) = write {
        if (libraryDao.findById(entryId) == null) return@write
        val now = System.currentTimeMillis()
        libraryDao.updateProgress(entryId, progress, unitIndex, unitTitle, positionMs, status, now)
        syncDao.enqueue(
            SyncOutboxEntity(
                entityId = entryId,
                mediaType = mediaType,
                syncOp = SyncOp.PUSH_PROGRESS,
                dedupeKey = "$entryId:progress",
                payload = json.encodeToString(
                    LibraryEntryEntity.serializer(), libraryDao.findById(entryId)!!,
                ),
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
        if (libraryDao.softDelete(entryId) == 0) return@write
        syncDao.enqueue(
            SyncOutboxEntity(
                entityId = entryId,
                mediaType = MediaType.MUSIC,
                syncOp = SyncOp.DELETE,
                dedupeKey = "$entryId:delete",
            ),
        )
        requestSync()
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
        withContext(ioDispatcher) { AccountAccess.mutex.withLock { block() } }
}
