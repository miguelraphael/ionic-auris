package com.auris.app.data.repository

import android.content.Context
import com.auris.app.data.local.PlaylistDao
import com.auris.app.data.local.SyncDao
import com.auris.app.data.local.entity.PlaylistEntity
import com.auris.app.data.local.entity.PlaylistTrackEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

/**
 * Device-local user playlists backed by Room.
 *
 * ### What this replaces
 * Playlists used to live in the `music_collections` SharedPreferences JSON store. This
 * repository is the single source of truth now; [importIfNeeded] does the one-time import
 * (and deletes the prefs key) on first use. `MusicLocalCollections` in the music UI package
 * remains only as a temporary synchronous shim until the UI cuts over to this repository —
 * its writes after import are absorbed by the IGNORE dedupe, but its shelf does not emit
 * updates, so the UI should switch to collecting [observePlaylists].
 *
 * ### Ownership
 * Writes stamp the current [SyncDao] session's owner, or null (device-local) when signed
 * out. [observePlaylists] follows the session via `flatMapLatest`, so an account switch
 * re-scopes without UI re-collection. [observeTracks] is keyed by playlist id: the UI only
 * holds ids it learned from [observePlaylists], and ids are unguessable UUIDs.
 *
 * Track ids are YouTube video ids (the same value as `MusicRow.id`) — never stream URLs,
 * which are session-bound and expire. The UI maps its rows onto the primitive [addTrack]
 * parameters; this layer deliberately avoids depending on the UI's `MusicRow`.
 */
class PlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val syncDao: SyncDao,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    // ---- Reads ---------------------------------------------------------------

    fun observePlaylists(): Flow<List<PlaylistEntity>> = flow {
        importIfNeeded()
        emitAll(syncDao.observeSession().flatMapLatest { playlistDao.observePlaylists(it?.userId) })
    }

    fun observeTracks(playlistId: String): Flow<List<PlaylistTrackEntity>> = flow {
        importIfNeeded()
        emitAll(playlistDao.observeTracks(playlistId))
    }

    // ---- Writes --------------------------------------------------------------

    /** Creates an empty playlist stamped with the current owner (null when signed out). */
    suspend fun createPlaylist(name: String): PlaylistEntity = write {
        val playlist = PlaylistEntity(
            name = name,
            userId = syncDao.currentSession()?.userId,
        )
        playlistDao.insertPlaylist(playlist)
        playlist
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        write { playlistDao.renamePlaylist(playlistId, name) }
    }

    /** Deleting a playlist removes its tracks via `ON DELETE CASCADE`. */
    suspend fun deletePlaylist(playlistId: String) {
        write { playlistDao.deletePlaylist(playlistId) }
    }

    /**
     * Appends a track. [trackId] is the YouTube video id (`MusicRow.id`), never a stream
     * URL. Adding the same video id twice keeps one row.
     */
    suspend fun addTrack(
        playlistId: String,
        trackId: String,
        title: String,
        artist: String? = null,
        artworkUrl: String? = null,
    ) {
        write { playlistDao.appendTrack(playlistId, trackId, title, artist, artworkUrl) }
    }

    suspend fun removeTrack(playlistId: String, trackId: String) {
        write { playlistDao.removeTrack(playlistId, trackId) }
    }

    /** Positions are the 0-based indexes of the list from [observeTracks]. */
    suspend fun moveTrack(playlistId: String, fromPosition: Int, toPosition: Int) {
        write { playlistDao.moveTrack(playlistId, fromPosition, toPosition) }
    }

    /**
     * One-time import from the legacy `music_collections` prefs.
     *
     * Reads the same JSON shape `MusicLocalCollections` wrote, inserts ordered rows
     * (position = array index) stamped with the current session owner, and deletes the
     * prefs key. Inserts are IGNORE-conflicting, so a re-run after the UI shim (or the
     * import itself) cannot duplicate anything. Once the key is gone this is a cheap no-op.
     */
    suspend fun importIfNeeded() = withContext(ioDispatcher) {
        importMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(PREFS_KEY, null)
            if (raw.isNullOrBlank()) return@withContext
            val array = JSONArray(raw)
            val owner = syncDao.currentSession()?.userId
            val now = System.currentTimeMillis()
            (0 until array.length()).forEach { index ->
                val item = array.getJSONObject(index)
                val playlist = PlaylistEntity(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    userId = owner,
                    createdAt = now,
                    updatedAt = now,
                )
                playlistDao.insertPlaylist(playlist)
                val tracks = item.getJSONArray("tracks")
                val rows = (0 until tracks.length()).mapNotNull { trackIndex ->
                    runCatching { tracks.getJSONObject(trackIndex) }.getOrNull()
                }
                    .distinctBy { it.getString("id") }
                    .mapIndexed { trackIndex, track ->
                        PlaylistTrackEntity(
                            playlistId = playlist.id,
                            position = trackIndex,
                            trackId = track.getString("id"),
                            title = track.getString("title"),
                            artist = track.optString("artist").takeIf { it.isNotBlank() },
                            artworkUrl = track.optString("art").takeIf { it.isNotBlank() },
                            updatedAt = now,
                        )
                    }
                if (rows.isNotEmpty()) playlistDao.insertTracks(rows)
            }
            prefs.edit().remove(PREFS_KEY).apply()
        }
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        withContext(ioDispatcher) { importIfNeeded(); block() }

    companion object {
        /** Matches `MusicLocalCollections`'s prefs name/key so the import reads the same store. */
        internal const val PREFS_NAME = "music_collections"
        internal const val PREFS_KEY = "playlists"
    }

    private val importMutex = Mutex()
}
