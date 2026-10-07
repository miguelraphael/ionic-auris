package com.auris.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.auris.app.AurisApplication
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.SyncOp
import com.auris.app.data.remote.SupabaseLibraryDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

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
    private val remote: SupabaseLibraryDataSource get() = container.supabaseLibraryDataSource
    private val json: Json get() = container.json

    override suspend fun doWork(): Result {
        val session = syncDao.currentSession()
            ?: return Result.success() // Signed out: nothing to push, and pulling would 401.

        return try {
            val pushed = push(session.userId)
            val pulled = pull(session.userId)
            syncDao.purgeExhausted(MAX_ATTEMPTS)
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

    /** Pushes pending outbox rows. Returns the number confirmed by the server. */
    private suspend fun push(userId: String): Int {
        val pending = syncDao.nextPending(BATCH_SIZE)
        if (pending.isEmpty()) return 0

        var confirmed = 0
        for (op in pending) {
            try {
                when (op.syncOp) {
                    SyncOp.UPSERT, SyncOp.PUSH_PROGRESS -> {
                        // The payload is written by the repository in the same transaction
                        // as the row. A missing payload means this op was superseded by a
                        // newer deduped one, so it is safe to drop.
                        val payload = op.payload
                        if (payload == null) {
                            syncDao.dequeue(op.id)
                            continue
                        }
                        val entry = json.decodeFromString(
                            LibraryEntryEntity.serializer(), payload,
                        )
                        remote.upsertEntry(userId, entry)
                        syncDao.markEntriesSynced(listOf(entry.id), System.currentTimeMillis())
                    }

                    SyncOp.DELETE -> remote.deleteEntry(userId, op.entityId)

                    else -> Unit // Unknown op from a newer app version: drop it.
                }
                syncDao.dequeue(op.id)
                confirmed++
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
    private suspend fun pull(userId: String): Int {
        val since = syncDao.currentSession()?.lastPulledAt
        val remoteEntries = remote.fetchEntries(userId, since)
        if (remoteEntries.isEmpty()) return 0

        container.libraryDao.upsertAll(remoteEntries)
        syncDao.markPulled(userId, System.currentTimeMillis())
        return remoteEntries.size
    }

    companion object {
        const val KEY_PUSHED = "pushed"
        const val KEY_PULLED = "pulled"

        private const val BATCH_SIZE = 200
        private const val MAX_ATTEMPTS = 8
    }
}

/** 5xx, rate limits, connectivity — safe to retry. */
class TransientSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** 4xx or a schema mismatch — retrying cannot succeed. */
class PermanentSyncException(message: String, cause: Throwable? = null) :
    Exception(message, cause)