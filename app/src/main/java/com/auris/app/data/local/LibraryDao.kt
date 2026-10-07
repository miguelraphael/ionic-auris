package com.auris.app.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.LibraryCategory
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import kotlinx.coroutines.flow.Flow

/**
 * A library row plus its units, used by detail screens in one Room round trip.
 */
data class LibraryEntryWithUnits(
    @Embedded val entry: LibraryEntryEntity,
    val units: List<LibraryUnitEntity> = emptyList(),
)

/**
 * Read path for the UI. Every query returns [Flow] so Compose recomposes off Room's
 * invalidation tracker — no manual refresh calls anywhere.
 */
@Dao
interface LibraryDao {

    // ---- Reads ---------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM library_entries WHERE is_deleted = 0 ORDER BY last_activity_at DESC")
    fun observeLibrary(): Flow<List<LibraryEntryEntity>>

    /** Filtered + sorted by a single CASE expression so the DB does the ranking. */
    @Transaction
    @Query(
        """
        SELECT * FROM library_entries
        WHERE is_deleted = 0
          AND media_type = :mediaType
        ORDER BY
            CASE WHEN :category IS NULL THEN 0
                 WHEN categories LIKE '%' || :separator || :category || :separator || '%'
                   OR categories = :category THEN 0
                 ELSE 1 END ASC,
            CASE :sort WHEN 'TITLE_ASC' THEN title END COLLATE NOCASE ASC,
            CASE :sort WHEN 'RECENT'  THEN last_activity_at END DESC,
            CASE :sort WHEN 'ADDED'   THEN created_at END DESC,
            last_activity_at DESC
        """
    )
    fun observeByType(
        mediaType: MediaType,
        category: LibraryCategory? = null,
        sort: String = "RECENT",
        // `separator` must match Converters.CATEGORY_SEPARATOR so the LIKE anchors
        // on a real delimiter instead of matching a category-name substring.
        separator: String = Converters.CATEGORY_SEPARATOR,
    ): Flow<List<LibraryEntryEntity>>

    /** The "continue" shelf: anything in progress, newest activity first. */
    @Transaction
    @Query(
        """
        SELECT * FROM library_entries
        WHERE is_deleted = 0 AND status = 'IN_PROGRESS' AND progress > 0
        ORDER BY last_activity_at DESC
        LIMIT :limit
        """
    )
    fun observeInProgress(limit: Int = 20): Flow<List<LibraryEntryEntity>>

    @Transaction
    @Query(
        """
        SELECT * FROM library_entries
        WHERE is_deleted = 0 AND is_favorite = 1
        ORDER BY last_activity_at DESC
        """
    )
    fun observeFavorites(): Flow<List<LibraryEntryEntity>>

    @Transaction
    @Query("SELECT * FROM library_entries WHERE id = :id")
    fun observeById(id: String): Flow<LibraryEntryEntity?>

    @Transaction
    @Query("SELECT * FROM library_entries WHERE id = :id")
    suspend fun findById(id: String): LibraryEntryEntity?

    @Transaction
    @Query("SELECT * FROM library_entries WHERE kitsu_id = :kitsuId LIMIT 1")
    suspend fun findByKitsuId(kitsuId: String): LibraryEntryEntity?

    /** Kitsu / extension reconciliation: all rows pointing at one remote media id. */
    @Transaction
    @Query(
        """
        SELECT * FROM library_entries
        WHERE media_type = :mediaType AND remote_media_id = :remoteMediaId AND is_deleted = 0
        """
    )
    suspend fun findByRemoteMediaId(
        mediaType: MediaType,
        remoteMediaId: String,
    ): List<LibraryEntryEntity>

    @Query(
        """
        SELECT COUNT(*) FROM library_entries
        WHERE media_type = :mediaType AND is_deleted = 0 AND is_favorite = 1
        """
    )
    fun observeFavoriteCount(mediaType: MediaType): Flow<Int>

    // ---- Units ---------------------------------------------------------------

    @Query("SELECT * FROM library_units WHERE entry_id = :entryId ORDER BY unit_index ASC")
    fun observeUnits(entryId: String): Flow<List<LibraryUnitEntity>>

    @Query(
        """
        SELECT * FROM library_units
        WHERE entry_id = :entryId AND is_watched = 1
        ORDER BY unit_index DESC
        """
    )
    fun observeWatchedUnits(entryId: String): Flow<List<LibraryUnitEntity>>

    // ---- Writes (local-first) -------------------------------------------------

    /**
     * Conflict strategy is ABORT so that the unique index on
     * (media_type, source_id, source_key) surfaces duplicates as a bug rather than
     * silently overwriting one user's row with another.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: LibraryEntryEntity): Long

    @Upsert
    suspend fun upsert(entry: LibraryEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<LibraryEntryEntity>)

    @Upsert
    suspend fun upsertUnits(units: List<LibraryUnitEntity>)

    @Query("DELETE FROM library_units WHERE entry_id = :entryId")
    suspend fun deleteUnitsFor(entryId: String)

    /**
     * Toggle favourite. Marks the row pending so the change reaches Supabase even if the
     * user never navigates away.
     */
    @Query(
        """
        UPDATE library_entries
        SET is_favorite = :favorite,
            sync_state = 'PENDING_UPLOAD',
            updated_at = :now,
            last_activity_at = :now
        WHERE id = :id
        """
    )
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long): Int

    /** Lightweight progress write — this is on the player's hot path, so it stays a single UPDATE. */
    @Query(
        """
        UPDATE library_entries
        SET progress = :progress,
            current_unit_index = :unitIndex,
            current_unit_title = :unitTitle,
            last_position_ms = :positionMs,
            status = CASE WHEN :status IS NULL THEN status ELSE :status END,
            updated_at = :now,
            last_activity_at = :now,
            sync_state = 'PENDING_UPLOAD'
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        progress: Float,
        unitIndex: Int,
        unitTitle: String?,
        positionMs: Long,
        status: EntryStatus?,
        now: Long = System.currentTimeMillis(),
    )

    @Query(
        """
        UPDATE library_units
        SET is_watched = :watched, last_position_ms = :positionMs, updated_at = :now
        WHERE entry_id = :entryId AND unit_index = :unitIndex
        """
    )
    suspend fun updateUnitProgress(
        entryId: String,
        unitIndex: Int,
        watched: Boolean,
        positionMs: Long,
        now: Long = System.currentTimeMillis(),
    )

    /** Soft delete so the tombstone can be pushed to Supabase before the row disappears. */
    @Query(
        """
        UPDATE library_entries
        SET is_deleted = 1, sync_state = 'PENDING_UPLOAD', updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis()): Int

    /**
     * Removes a tombstone for good, once Supabase has confirmed the delete (or the user
     * signed out). Units go with it via `ON DELETE CASCADE`.
     *
     * Kept separate from [softDelete] on purpose: nothing in the UI path may hard-delete,
     * or a deleted item would reappear on the next pull.
     */
    @Query("DELETE FROM library_entries WHERE id = :id AND is_deleted = 1")
    suspend fun purgeTombstone(id: String): Int

    /**
     * Deletes tombstoned rows whose delete has already been confirmed by the server.
     * Called by the sync worker after a successful DELETE push.
     */
    @Query(
        """
        DELETE FROM library_entries
        WHERE is_deleted = 1 AND sync_state = 'SYNCED' AND updated_at < :before
        """
    )
    suspend fun purgeSyncedTombstones(before: Long): Int

    @Query("UPDATE library_entries SET sync_state = :state, remote_updated_at = :remoteAt WHERE id = :id")
    suspend fun markSyncState(id: String, state: SyncState, remoteAt: Long?)

    // ---- Search --------------------------------------------------------------

    /**
     * Free-text search over title and author.
     *
     * SQLite's `LIKE` folds case for ASCII by default, which is what makes "frien" match
     * "Frieren" without an explicit `lower()`. Neither this form nor any leading-wildcard
     * form can use an index, so there is nothing to gain by restructuring the predicate.
     *
     * NOTE: covered by `LibrarySearchTest` (androidTest) rather than the Robolectric suite.
     * Robolectric's bundled SQLite does not implement `LIKE`/`instr` pattern matching
     * correctly — verified: `instr('Frieren', 'rien')` returns 0 there while it returns 3 on
     * a real device — so asserting search under Robolectric would test the harness, not the
     * query. The query itself is correct.
     */
    @Transaction
    @Query(
        """
        SELECT * FROM library_entries
        WHERE is_deleted = 0
          AND (title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%')
        ORDER BY last_activity_at DESC
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int = 50): Flow<List<LibraryEntryEntity>>
}