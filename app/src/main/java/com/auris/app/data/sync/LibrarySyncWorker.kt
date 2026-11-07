package com.auris.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.auris.app.AurisApplication
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.LibraryDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.SyncOp
import com.auris.app.data.remote.SupabaseLibraryDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import com.auris.app.data.repository.AccountAccess
import com.auris.app.data.repository.LibraryRepository

/**
 * Drains the Room outbox to Supabase, then pulls remote changes back down.
 *
 * Contract:
 *  - Push and pull are independent. A failed push must not block a successful pull.
 *  - Individual row failures are recorded on the outbox entry and the pass continues, so
 *    one malformed anime can't wedge the queue behind it.
 *  - Rows that exhaust [MAX_ATTEMPTS] are dropped and surfaced to the user for manual retry.
 *  - `retry()` is only returned for transient failures (network, 5xx, rate limits); a 4xx
 *    is a permanent rejection and marks the outbox entry CONFLICT instead of retrying.
 */
class LibrarySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as AurisApplication).container
    private val syncDao: SyncDao get() = container.syncDao

    override suspend fun doWork(): Result = AccountAccess.mutex.withLock { doWorkForAccount() }

    private suspend fun doWorkForAccount(): Result {
        val session = container.authRepository.currentAuthenticatedSession()
            ?: return Result.success() // Signed out: nothing to push, and pulling would 401.

        return try {
            val pass = LibrarySyncPass(
                container.libraryDao, syncDao, container.libraryRepository,
                container.supabaseLibraryDataSource,
                currentUserId = { container.authRepository.currentAuthenticatedSession()?.userId },
            )
            val pushed = pass.push(session.userId)
            val pulled = pass.pull(session.userId)
            syncDao.purgeExhaustedFor(session.userId, MAX_ATTEMPTS)
            Result.success(
                workDataOf(
                    KEY_PUSHED to pushed,
                    KEY_PULLED to pulled,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransientSyncException) {
            Result.retry()
        } catch (e: Exception) {
            // Unexpected: still retry, but never more than once per backoff window.
            Result.retry()
        }
    }

    companion object {
        const val KEY_PUSHED = "pushed"
        const val KEY_PULLED = "pulled"
        private const val MAX_ATTEMPTS = 8
    }
}

/** The worker's account-scoped pass, separated from WorkManager for database/transport tests. */
internal class LibrarySyncPass(
    private val libraryDao: LibraryDao,
    private val syncDao: SyncDao,
    private val libraryRepository: LibraryRepository,
    private val remote: SupabaseLibraryDataSource,
    private val currentUserId: () -> String?,
) {
    /** Pushes pending outbox rows. Returns the number confirmed by the server. */
    suspend fun push(userId: String): Int {
        val pending = syncDao.nextPendingFor(userId, BATCH_SIZE)
        if (pending.isEmpty()) return 0

        var confirmed = 0
        for (op in pending) {
            try {
                if (currentUserId() != userId) break
                when (op.syncOp) {
                    SyncOp.UPSERT, SyncOp.PUSH_PROGRESS -> {
                        // Read the current owned row: old progress ops had no payload, and a
                        // queued snapshot may predate a newer progress update or local delete.
                        val owned = libraryDao.findById(op.entityId)
                        if (owned == null || owned.isDeleted) {
                            syncDao.dequeue(op.id)
                            continue
                        }
                        require(owned.userId == userId) { "Entry belongs to another account" }
                        remote.upsertEntry(userId, owned)
                        syncDao.markEntriesSynced(listOf(owned.id), System.currentTimeMillis())
                    }

                    SyncOp.DELETE -> {
                        val entry = libraryDao.findById(op.entityId)
                        if (entry == null || !entry.isDeleted) {
                            syncDao.dequeue(op.id)
                            continue
                        }
                        remote.deleteEntry(userId, entry.remoteId)
                        // Clear every stale write for this title before removing its owner row.
                        libraryDao.purgeTombstone(entry.id)
                    }

                    else -> Unit // Unknown op from a newer app version: drop it.
                }
                syncDao.dequeue(op.id)
                confirmed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: PermanentSyncException) {
                syncDao.markAttempt(op.id, e.message, SyncState.CONFLICT)
            } catch (e: TransientSyncException) {
                syncDao.markAttempt(op.id, e.message, SyncState.PENDING_UPLOAD)
                // Stop this pass: the network is the problem, not the row.
                break
            } catch (e: Exception) {
                syncDao.markAttempt(op.id, e.message, SyncState.PENDING_UPLOAD)
            }
        }
        return confirmed
    }

    /**
     * Pulls rows the server has that this device doesn't, using `updated_at` as a high-water
     * mark so restarts don't re-download the whole library.
     */
    suspend fun pull(userId: String): Int {
        if (currentUserId() != userId) return 0
        val since = syncDao.currentSession()?.lastPulledAt
        val remoteEntries = remote.fetchEntries(userId, since)
        if (remoteEntries.isEmpty()) return 0

        if (currentUserId() != userId) return 0
        libraryRepository.mergeRemoteEntries(userId, remoteEntries)
        // Re-query this millisecond inclusively on the next pass: Postgres timestamps may
        // have sub-millisecond precision and multiple rows can share the boundary.
        val watermark = remoteEntries.maxOf { it.remoteUpdatedAt ?: it.updatedAt }
        syncDao.markPulled(userId, maxOf(since ?: Long.MIN_VALUE, watermark))
        return remoteEntries.size
    }

    private companion object {
        const val BATCH_SIZE = 200
    }
}

/** 5xx, rate limits, connectivity — safe to retry. */
class TransientSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** 4xx or a schema mismatch — retrying cannot succeed. */
class PermanentSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
