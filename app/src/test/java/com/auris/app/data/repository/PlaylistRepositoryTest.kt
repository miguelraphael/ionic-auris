package com.auris.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.data.local.AurisDatabase
import com.auris.app.data.local.entity.SyncSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Covers [PlaylistRepository]: the one-time SharedPreferences import, owner stamping and
 * dedupe on the write path.
 *
 * In-memory Room under Robolectric, same style as `LibraryDaoTest`. The SharedPreferences
 * fake is real enough that seeding the exact JSON shape `MusicLocalCollections` wrote —
 * and asserting its deletion afterward — is a genuine round trip.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class PlaylistRepositoryTest {

    private lateinit var db: AurisDatabase
    private lateinit var repo: PlaylistRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(
            context,
            AurisDatabase::class.java,
        ).allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
        repo = PlaylistRepository(
            playlistDao = db.playlistDao(),
            syncDao = db.syncDao(),
            context = context.applicationContext,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun seedPrefs(json: String) {
        context.getSharedPreferences(PlaylistRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PlaylistRepository.PREFS_KEY, json).commit()
    }

    private fun readPrefsKey(): String? =
        context.getSharedPreferences(PlaylistRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PlaylistRepository.PREFS_KEY, null)

    @Test
    fun importMovesPrefsPlaylistsIntoRoomAndClearsKey() = runTest {
        db.syncDao().saveSession(SyncSessionEntity("account-a"))
        seedPrefs(
            """
            [{"id":"p1","name":"Road trip","tracks":[
                {"id":"video-1","title":"One","artist":"Artist A","art":"https://img.test/1.jpg"},
                {"id":"video-2","title":"Two","artist":"","art":""},
                {"id":"video-1","title":"One","artist":"Artist A","art":"https://img.test/1.jpg"}
            ]},{"id":"p2","name":"Evening","tracks":[]}]
            """.trimIndent(),
        )

        repo.importIfNeeded()

        val playlists = repo.observePlaylists().first()
        assertEquals(listOf("p1", "p2"), playlists.map { it.id })
        assertEquals("account-a", playlists.first().userId)

        val tracks = repo.observeTracks("p1").first()
        assertEquals(listOf("video-1", "video-2"), tracks.map { it.trackId })
        assertEquals(listOf(0, 1), tracks.map { it.position })
        assertEquals("Artist A", tracks.first().artist)
        assertEquals("https://img.test/1.jpg", tracks.first().artworkUrl)
        assertNull(tracks[1].artist)
        assertNull(tracks[1].artworkUrl)
        assertTrue(repo.observeTracks("p2").first().isEmpty())

        assertNull(readPrefsKey())

        // A second run is a no-op now that the key is gone.
        repo.importIfNeeded()
        assertEquals(2, repo.observePlaylists().first().size)
    }

    @Test
    fun importWithoutSessionLeavesPlaylistsDeviceLocal() = runTest {
        seedPrefs(
            """[{"id":"p1","name":"Local","tracks":[{"id":"video-1","title":"One","artist":"A","art":""}]}]""",
        )

        repo.importIfNeeded()

        val playlists = repo.observePlaylists().first()
        assertEquals(listOf("p1"), playlists.map { it.id })
        assertNull(playlists.single().userId)
    }

    @Test
    fun repositoryCrudStampsOwnerAndDedupesTracks() = runTest {
        db.syncDao().saveSession(SyncSessionEntity("account-a"))

        val created = repo.createPlaylist("Road trip")
        assertEquals("account-a", created.userId)

        repo.addTrack(created.id, "video-1", "One", "Artist", null)
        repo.addTrack(created.id, "video-1", "One", "Artist", null)
        repo.addTrack(created.id, "video-2", "Two", null, null)
        assertEquals(2, repo.observeTracks(created.id).first().size)

        repo.renamePlaylist(created.id, "Renamed")
        assertEquals("Renamed", repo.observePlaylists().first().single().name)

        repo.removeTrack(created.id, "video-1")
        assertEquals(listOf("video-2"), repo.observeTracks(created.id).first().map { it.trackId })

        repo.deletePlaylist(created.id)
        assertTrue(repo.observePlaylists().first().isEmpty())
        assertTrue(repo.observeTracks(created.id).first().isEmpty())
    }

    @Test
    fun observePlaylistsRescopesOnAccountSwitch() = runTest {
        db.syncDao().saveSession(SyncSessionEntity("account-a"))
        val created = repo.createPlaylist("Mine")

        db.syncDao().clearSession()
        db.syncDao().saveSession(SyncSessionEntity("account-b"))
        assertTrue(repo.observePlaylists().first().isEmpty())

        db.syncDao().clearSession()
        assertTrue(repo.observePlaylists().first().isEmpty())

        // The playlist survives the switch — it is just hidden from other owners.
        db.syncDao().saveSession(SyncSessionEntity("account-a"))
        assertEquals(listOf(created.id), repo.observePlaylists().first().map { it.id })
    }
}
