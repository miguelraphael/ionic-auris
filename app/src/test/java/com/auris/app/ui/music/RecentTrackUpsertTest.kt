package com.auris.app.ui.music

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.AurisDatabase
import com.auris.app.data.local.entity.SyncSessionEntity
import com.auris.app.data.remote.music.MusicLibraryMapper
import com.auris.app.data.remote.music.MusicTrack
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.data.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Queued tracks, not just the first one, are persisted into Recently Played.
 *
 * Runs against an in-memory Room database so the upsert genuinely executes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class RecentTrackUpsertTest {
    private lateinit var db: AurisDatabase
    private lateinit var repo: LibraryRepository

    private fun track(videoId: String, title: String) = MusicTrack(
        videoId = videoId, title = title, artist = null, album = null, durationMs = null,
        artworkUrl = null, largeArtworkUrl = null, playlistId = null, trackIndex = null,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AurisDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = LibraryRepository(db.libraryDao(), db.syncDao(), SyncScheduler(context),
            Dispatchers.Unconfined, Json, requestSync = {})
        runBlocking { db.syncDao().saveSession(SyncSessionEntity("account-a")) }
    }

    @After
    fun tearDown() = db.close()

    @Test fun secondInsertedTrackIsUpserted() = runTest {
        upsertRecentTrack(repo, track("video-1", "First"))
        upsertRecentTrack(repo, track("video-2", "Second"))

        assertNotNull(repo.findBySource(MediaType.MUSIC, "video-1", MusicLibraryMapper.SOURCE_KEY))
        assertNotNull(repo.findBySource(MediaType.MUSIC, "video-2", MusicLibraryMapper.SOURCE_KEY))
        val titles = repo.observeLibrary().first().map { it.title }.toSet()
        assertTrue(titles.contains("First"))
        assertTrue(titles.contains("Second"))
    }

    @Test fun reUpsertPreservesFavorite() = runTest {
        upsertRecentTrack(repo, track("video-1", "First"))
        val entry = repo.findBySource(MediaType.MUSIC, "video-1", MusicLibraryMapper.SOURCE_KEY)!!
        repo.setFavorite(entry.id, true)

        upsertRecentTrack(repo, track("video-1", "First"))

        val after = repo.findBySource(MediaType.MUSIC, "video-1", MusicLibraryMapper.SOURCE_KEY)!!
        assertEquals(entry.id, after.id)
        assertTrue(after.isFavorite)
    }
}
