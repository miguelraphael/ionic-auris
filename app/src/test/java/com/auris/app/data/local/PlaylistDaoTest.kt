package com.auris.app.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Covers the playlist DAO: owner scoping, dedupe, ordering, moves and the CASCADE delete.
 *
 * Runs under Robolectric against a real in-memory SQLite database, so the DAO's SQL is
 * genuinely parsed and executed rather than mocked, in the same style as [LibraryDaoTest].
 *
 * The builder adds the same `PRAGMA foreign_keys = ON` callback production uses
 * (`AurisDatabase.build`): without it the cascade delete would not be enforced on this
 * connection, and the test would be asserting a weaker contract than the device gets.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class PlaylistDaoTest {

    private lateinit var db: AurisDatabase
    private lateinit var dao: PlaylistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AurisDatabase::class.java,
        ).allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
        dao = db.playlistDao()
        kotlinx.coroutines.runBlocking { db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-a")) }
    }

    @After
    fun tearDown() = db.close()

    private fun playlist(
        id: String = "playlist-1",
        name: String = "Road trip",
        userId: String? = "account-a",
    ) = PlaylistEntity(id = id, name = name, userId = userId)

    @Test
    fun createListRenameDelete() = runTest {
        dao.insertPlaylist(playlist())

        val listed = dao.observePlaylists("account-a").first()
        assertEquals(listOf("Road trip"), listed.map { it.name })

        dao.renamePlaylist("playlist-1", "Renamed")
        assertEquals("Renamed", dao.observePlaylists("account-a").first().single().name)

        dao.deletePlaylist("playlist-1")
        assertTrue(dao.observePlaylists("account-a").first().isEmpty())
    }

    @Test
    fun playlistsAreScopedByOwner() = runTest {
        dao.insertPlaylist(playlist(id = "a", userId = "account-a"))
        dao.insertPlaylist(playlist(id = "b", userId = "account-b"))
        dao.insertPlaylist(playlist(id = "local", userId = null))

        assertEquals(listOf("a"), dao.observePlaylists("account-a").first().map { it.id })
        assertEquals(listOf("b"), dao.observePlaylists("account-b").first().map { it.id })
        assertEquals(listOf("local"), dao.observePlaylists(null).first().map { it.id })
    }

    @Test
    fun addingSameVideoIdTwiceKeepsOneRow() = runTest {
        dao.insertPlaylist(playlist())
        dao.appendTrack("playlist-1", "video-1", "Song One", "Artist", null)
        dao.appendTrack("playlist-1", "video-1", "Song One", "Artist", null)

        val tracks = dao.observeTracks("playlist-1").first()
        assertEquals(1, tracks.size)
        assertEquals("video-1", tracks.single().trackId)
        assertEquals(0, tracks.single().position)
    }

    @Test
    fun tracksAreOrderedAndRemoveTrackPreservesOrder() = runTest {
        dao.insertPlaylist(playlist())
        dao.appendTrack("playlist-1", "video-1", "One", null, null)
        dao.appendTrack("playlist-1", "video-2", "Two", null, null)
        dao.appendTrack("playlist-1", "video-3", "Three", null, null)

        dao.removeTrack("playlist-1", "video-2")

        assertEquals(listOf("video-1", "video-3"), dao.observeTracks("playlist-1").first().map { it.trackId })
    }

    @Test
    fun moveTrackReordersAndRewritesPositions() = runTest {
        dao.insertPlaylist(playlist())
        dao.appendTrack("playlist-1", "video-1", "One", null, null)
        dao.appendTrack("playlist-1", "video-2", "Two", null, null)
        dao.appendTrack("playlist-1", "video-3", "Three", null, null)

        dao.moveTrack("playlist-1", fromPosition = 0, toPosition = 2)

        val tracks = dao.observeTracks("playlist-1").first()
        assertEquals(listOf("video-2", "video-3", "video-1"), tracks.map { it.trackId })
        assertEquals(listOf(0, 1, 2), tracks.map { it.position })
    }

    @Test
    fun deletingPlaylistCascadesTracks() = runTest {
        dao.insertPlaylist(playlist())
        dao.appendTrack("playlist-1", "video-1", "One", null, null)
        dao.appendTrack("playlist-1", "video-2", "Two", null, null)

        dao.deletePlaylist("playlist-1")

        assertTrue(dao.observeTracks("playlist-1").first().isEmpty())
        assertTrue(dao.tracksOrdered("playlist-1").isEmpty())
    }

    /**
     * The JVM mirror of the on-device `MigrationsTest` 5→6 case (which cannot run here):
     * applying the migration over an existing library row must leave the row untouched
     * and create the two new tables empty.
     */
    @Test
    fun migration5To6KeepsLibraryRowsAndLeavesNewTablesEmpty() = runTest {
        db.libraryDao().upsert(
            LibraryEntryEntity(
                userId = "account-a",
                mediaType = MediaType.MUSIC,
                sourceId = "video-9",
                sourceKey = "innertube",
                title = "Survivor",
            ),
        )

        Migrations.MIGRATION_5_6.migrate(db.openHelper.writableDatabase)

        assertEquals("Survivor", db.libraryDao().findById(
            db.libraryDao().observeLibrary().first().single().id,
        )!!.title)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM playlists").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM playlist_tracks").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
}
