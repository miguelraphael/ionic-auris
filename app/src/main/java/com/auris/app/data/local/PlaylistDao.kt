package com.auris.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.auris.app.data.local.entity.PlaylistEntity
import com.auris.app.data.local.entity.PlaylistTrackEntity
import kotlinx.coroutines.flow.Flow

/**
 * Playlist reads and writes.
 *
 * ### Owner scoping (decision)
 * Reads take the owner as a parameter ([observePlaylists]) instead of the
 * `user_id = (SELECT user_id FROM sync_sessions LIMIT 1)` subquery `LibraryDao` uses.
 * The library hard-requires sign-in, but playlists must work signed out: with no session,
 * the subquery is NULL and `user_id = NULL` is never true, so every device-local playlist
 * would be invisible. The `IS :userId` form matches NULL=NULL, giving "signed in → mine,
 * signed out → device-local" with a single parameter. [PlaylistRepository] resolves the
 * current owner through `SyncDao.observeSession()` (re-collecting on an account switch)
 * and passes it here.
 *
 * Writes are keyed by playlist id only. Ids are unguessable UUIDs the UI obtains from the
 * owner-scoped [observePlaylists] flow, so ownership is enforced at the read; adding the
 * owner again per write would duplicate plumbing without a real threat model here.
 */
@Dao
interface PlaylistDao {

    // ---- Reads ---------------------------------------------------------------

    @Query("SELECT * FROM playlists WHERE user_id IS :userId ORDER BY updated_at DESC")
    fun observePlaylists(userId: String?): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position ASC")
    fun observeTracks(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlist_id = :playlistId ORDER BY position ASC")
    suspend fun tracksOrdered(playlistId: String): List<PlaylistTrackEntity>

    // ---- Writes --------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updated_at = :now WHERE id = :playlistId")
    suspend fun renamePlaylist(
        playlistId: String,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): Int

    /** The playlist's tracks go with it via `ON DELETE CASCADE` on `playlist_tracks`. */
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String): Int

    /**
     * IGNORE on duplicate (playlistId, trackId): adding the same video id twice keeps one
     * row, matching the old store's `distinctBy`. Callers that need end-append semantics
     * use [appendTrack], which supplies [position].
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrack(track: PlaylistTrackEntity): Long

    /** Appends [trackId] at the end, computing the next position inside the transaction. */
    @Transaction
    suspend fun appendTrack(
        playlistId: String,
        trackId: String,
        title: String,
        artist: String?,
        artworkUrl: String?,
    ) {
        addTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                position = nextPosition(playlistId),
                trackId = trackId,
                title = title,
                artist = artist,
                artworkUrl = artworkUrl,
            ),
        )
    }

    /** Gaps in [position] are fine: ordering is relative, so no compaction is needed. */
    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeTrack(playlistId: String, trackId: String): Int

    /**
     * Reorders one track. The composite primary key (playlistId, position) forbids swapping
     * two rows' positions in place — the second UPDATE would briefly collide — so this
     * rewrites positions wholesale: read ordered, reorder in memory, delete and re-insert
     * with fresh 0-based positions. Playlists are small; the transaction keeps it atomic.
     */
    @Transaction
    suspend fun moveTrack(playlistId: String, fromPosition: Int, toPosition: Int) {
        val tracks = tracksOrdered(playlistId)
        if (fromPosition !in tracks.indices ||
            toPosition !in tracks.indices ||
            fromPosition == toPosition
        ) {
            return
        }
        val ordered = tracks.toMutableList()
        ordered.add(toPosition, ordered.removeAt(fromPosition))
        deleteTracks(playlistId)
        insertTracks(ordered.mapIndexed { index, track -> track.copy(position = index) })
    }

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun deleteTracks(playlistId: String)

    /** IGNORE so the one-time import is safe to run more than once. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = :playlistId")
    suspend fun nextPosition(playlistId: String): Int
}
