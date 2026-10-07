package com.auris.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.entity.SyncOp
import com.auris.app.data.local.entity.SyncOutboxEntity
import com.auris.app.data.local.entity.SyncSessionEntity
import kotlinx.coroutines.flow.Flow

/** Result of one successful drain pass, surfaced in Settings → Sync. */
data class SyncStats(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
) {
    val isNoOp: Boolean get() = pushed == 0 && pulled == 0 && conflicts == 0
}

/**
 * Outbox + auth session access for the WorkManager sync worker.
 */
@Dao
interface SyncDao {

    // ---- Outbox --------------------------------------------------------------

    /**
     * REPLACE means the newest payload wins for a given dedupe key. Scrubbing the player
     * generates dozens of progress writes and collapses into a single queued UPSERT.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(op: SyncOutboxEntity): Long

    @Query(
        """
        SELECT * FROM sync_outbox
        WHERE sync_state = 'PENDING_UPLOAD'
        ORDER BY created_at ASC
        LIMIT :limit
        """
    )
    suspend fun nextPending(limit: Int = 200): List<SyncOutboxEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sync_outbox
        WHERE sync_state = 'PENDING_UPLOAD'
        """
    )
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE sync_state = 'PENDING_UPLOAD'")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun dequeue(id: Long)

    @Query(
        """
        UPDATE sync_outbox
        SET attempts = attempts + 1, last_error = :error, sync_state = :state
        WHERE id = :id
        """
    )
    suspend fun markAttempt(id: Long, error: String?, state: SyncState)

    /** Give up on rows that have failed too many times; surfaced to the user for a manual retry. */
    @Query("DELETE FROM sync_outbox WHERE attempts >= :maxAttempts")
    suspend fun purgeExhausted(maxAttempts: Int = 8): Int

    @Query("DELETE FROM sync_outbox")
    suspend fun clearOutbox()

    // ---- Session -------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: SyncSessionEntity)

    @Query("SELECT * FROM sync_sessions LIMIT 1")
    suspend fun currentSession(): SyncSessionEntity?

    @Query("SELECT * FROM sync_sessions LIMIT 1")
    fun observeSession(): Flow<SyncSessionEntity?>

    @Query("UPDATE sync_sessions SET last_pulled_at = :at WHERE user_id = :userId")
    suspend fun markPulled(userId: String, at: Long)

    @Query("DELETE FROM sync_sessions")
    suspend fun clearSession()

    /**
     * Marks a batch of entries synced in a single statement, so a 500-row catch-up pull
     * costs one UPDATE rather than 500.
     */
    @Query("UPDATE library_entries SET sync_state = 'SYNCED', remote_updated_at = :remoteAt WHERE id IN (:ids)")
    suspend fun markEntriesSynced(ids: List<String>, remoteAt: Long): Int
}