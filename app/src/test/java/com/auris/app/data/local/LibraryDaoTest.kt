package com.auris.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.auris.app.TestApplication
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
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
 * Verifies the unified favourites contract: one table serves all three media types, the
 * identity index rejects duplicates, and writes are observable through Flow.
 *
 * Runs under Robolectric against a real in-memory SQLite database, so the DAO's SQL is
 * genuinely parsed and executed rather than mocked.
 *
 * ### Scope
 * Covers structure, identity, ordering, cascade, type converters and Flow propagation.
 * Substring search is deliberately excluded: Robolectric's bundled SQLite does not implement
 * `LIKE`/`instr` pattern matching correctly — `instr('Frieren', 'rien')` returns 0 under
 * Robolectric and 3 on a real device — so those assertions live in the instrumented
 * `LibrarySearchTest`, which passes on device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class LibraryDaoTest {

    private lateinit var db: AurisDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AurisDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.libraryDao()
        kotlinx.coroutines.runBlocking { db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-a")) }
    }

    @After
    fun tearDown() = db.close()

    private fun entry(
        mediaType: MediaType = MediaType.ANIME,
        sourceKey: String = "1",
        title: String = "Test",
    ) = LibraryEntryEntity(
        userId = "account-a",
        mediaType = mediaType,
        sourceId = if (mediaType == MediaType.MUSIC) "innertube" else "example.org",
        sourceKey = sourceKey,
        title = title,
    )

    @Test
    fun accountSwitchHidesLibraryUnitsAndPendingUploadsWithoutDeletingThem() = runTest {
        val first = entry(sourceKey = "shared-title")
        val second = first.copy(id = "other-id", remoteId = "other-id", userId = "account-b")
        dao.upsert(first)
        dao.upsert(second)
        dao.upsertUnits(listOf(LibraryUnitEntity(entryId = first.id, unitIndex = 1, title = "Private episode")))
        db.syncDao().enqueue(com.auris.app.data.local.entity.SyncOutboxEntity(
            entityId = first.id, mediaType = MediaType.ANIME, syncOp = "UPSERT", dedupeKey = first.id))
        db.syncDao().clearSession()
        db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-b"))

        assertEquals(listOf(second.id), dao.observeLibrary().first().map { it.id })
        assertNull(dao.findById(first.id))
        assertTrue(dao.observeUnits(first.id).first().isEmpty())
        assertTrue(db.syncDao().nextPendingFor("account-b").isEmpty())
        assertEquals(1, db.syncDao().nextPendingFor("account-a").size)

        db.syncDao().clearSession()
        assertTrue(dao.observeLibrary().first().isEmpty())
        db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-a"))
        assertEquals(listOf(first.id), dao.observeLibrary().first().map { it.id })
        assertEquals(1, dao.observeUnits(first.id).first().size)
    }

    @Test
    fun migrationPreservesOwnedRowsUnitsAndOutboxAndErasesTokens() = runTest {
        val legacy = entry()
        dao.upsert(legacy)
        dao.upsertUnits(listOf(LibraryUnitEntity(entryId = legacy.id, unitIndex = 1, title = "Saved progress")))
        db.syncDao().enqueue(com.auris.app.data.local.entity.SyncOutboxEntity(
            entityId = legacy.id, mediaType = MediaType.ANIME, syncOp = "UPSERT", dedupeKey = legacy.id))
        db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-a", accessToken = "old-access", refreshToken = "old-refresh"))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP INDEX index_library_entries_user_id_media_type_source_id_source_key")
        sql.execSQL("CREATE UNIQUE INDEX index_library_entries_media_type_source_id_source_key ON library_entries(media_type, source_id, source_key)")

        Migrations.MIGRATION_4_5.migrate(sql)

        assertEquals("account-a", dao.findById(legacy.id)!!.userId)
        assertEquals(1, dao.observeUnits(legacy.id).first().size)
        assertEquals(1, db.syncDao().nextPendingFor("account-a").size)
        assertNull(db.syncDao().currentSession()!!.accessToken)
        assertNull(db.syncDao().currentSession()!!.refreshToken)
    }

    @Test
    fun migrationDoesNotGiveUnattributedRowsToTheNextLogin() = runTest {
        val legacy = entry().copy(userId = null)
        dao.upsert(legacy)
        db.syncDao().clearSession()
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DROP INDEX index_library_entries_user_id_media_type_source_id_source_key")
        sql.execSQL("CREATE UNIQUE INDEX index_library_entries_media_type_source_id_source_key ON library_entries(media_type, source_id, source_key)")

        Migrations.MIGRATION_4_5.migrate(sql)
        db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-b"))

        assertNull(dao.findById(legacy.id))
        sql.query("SELECT COUNT(*) FROM library_entries WHERE user_id IS NULL").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun savingSameMusicForTwoAccountsKeepsIndependentIdsAndUploads() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = com.auris.app.data.repository.LibraryRepository(dao, db.syncDao(),
            com.auris.app.data.sync.SyncScheduler(context), kotlinx.coroutines.Dispatchers.Unconfined,
            kotlinx.serialization.json.Json, requestSync = {})
        val mapped = com.auris.app.data.remote.music.MusicLibraryMapper.toEntry(
            com.auris.app.data.remote.music.MusicTrack(videoId = "video-1", title = "Shared song",
                artist = null, album = null, durationMs = null, artworkUrl = null,
                largeArtworkUrl = null, playlistId = null, trackIndex = null))
        val first = repo.upsert(mapped)
        db.syncDao().clearSession()
        db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-b"))
        val second = repo.upsert(mapped)

        assertTrue(first.id != second.id)
        assertTrue(first.remoteId != second.remoteId)
        assertEquals("account-a", first.userId)
        assertEquals("account-b", second.userId)
        assertEquals(first.id, db.syncDao().nextPendingFor("account-a").single().entityId)
        assertEquals(second.id, db.syncDao().nextPendingFor("account-b").single().entityId)
        assertEquals(second.id, repo.findBySource(MediaType.MUSIC, "video-1", "innertube")!!.id)
    }

    @Test
    fun staleProgressAndDeleteCannotReplaceAnotherAccountsPendingWork() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = com.auris.app.data.repository.LibraryRepository(dao, db.syncDao(),
            com.auris.app.data.sync.SyncScheduler(context), kotlinx.coroutines.Dispatchers.Unconfined,
            kotlinx.serialization.json.Json, requestSync = {})
        val first = repo.upsert(entry().copy(userId = null))
        val original = db.syncDao().nextPendingFor("account-a")
        db.syncDao().clearSession()
        db.syncDao().saveSession(com.auris.app.data.local.entity.SyncSessionEntity("account-b"))

        repo.updateProgress(first.id, 0.5f, 1, "Stale", 1000L, null)
        repo.delete(first.id)

        assertEquals(original, db.syncDao().nextPendingFor("account-a"))
        assertTrue(db.syncDao().nextPendingFor("account-b").isEmpty())
    }

    @Test
    fun cloudRestoreRemapsQuarantinedPrimaryKeysWithoutErasingUnsyncedData() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = com.auris.app.data.repository.LibraryRepository(dao, db.syncDao(),
            com.auris.app.data.sync.SyncScheduler(context), kotlinx.coroutines.Dispatchers.Unconfined,
            kotlinx.serialization.json.Json, requestSync = {})
        val legacy = entry().copy(userId = null, title = "Unsynced legacy text")
        dao.upsert(legacy)
        db.syncDao().enqueue(com.auris.app.data.local.entity.SyncOutboxEntity(
            entityId = legacy.id, mediaType = MediaType.ANIME, syncOp = "UPSERT", dedupeKey = legacy.id))
        val remote = legacy.copy(userId = "account-a", title = "Cloud snapshot")

        repo.upsertAll(listOf(remote))
        repo.upsertAll(listOf(remote))

        val restored = dao.observeLibrary().first().single()
        assertTrue(restored.id != legacy.id)
        assertEquals(legacy.remoteId, restored.remoteId)
        assertEquals("Cloud snapshot", restored.title)
        db.openHelper.writableDatabase.query("SELECT title FROM library_entries WHERE id = ?", arrayOf(legacy.id)).use {
            assertTrue(it.moveToFirst())
            assertEquals("Unsynced legacy text", it.getString(0))
        }
        assertEquals(1, db.syncDao().nextPending().size)
    }

    @Test
    fun allThreeMediaTypesShareOneTable() = runTest {
        dao.upsert(entry(MediaType.ANIME, "1", "Anime A"))
        dao.upsert(entry(MediaType.MANGA, "1", "Manga A"))
        dao.upsert(entry(MediaType.MUSIC, "yt1", "Track A"))

        val all = dao.observeLibrary().first()
        assertEquals(3, all.size)
        assertEquals(
            setOf(MediaType.ANIME, MediaType.MANGA, MediaType.MUSIC),
            all.map { it.mediaType }.toSet(),
        )
    }

    @Test
    fun sameMediaFromDifferentSourcesCoexists() = runTest {
        dao.upsert(entry(MediaType.ANIME, "1", "From source one").copy(sourceId = "a.org"))
        dao.upsert(entry(MediaType.ANIME, "1", "From source two").copy(sourceId = "b.org"))

        assertEquals(2, dao.observeLibrary().first().size)
    }

    @Test
    fun upsertReplacesRowForSameIdentity() = runTest {
        dao.upsert(entry(title = "Original"))
        val existing = dao.observeLibrary().first().single()

        dao.upsert(existing.copy(title = "Renamed"))

        val rows = dao.observeLibrary().first()
        assertEquals(1, rows.size)
        assertEquals("Renamed", rows.single().title)
    }

    @Test
    fun progressWriteIsImmediatelyObservable() = runTest {
        val row = entry()
        dao.upsert(row)

        dao.updateProgress(
            id = row.id,
            progress = 0.42f,
            unitIndex = 7,
            unitTitle = "Episode 7",
            positionMs = 900_000L,
            status = EntryStatus.IN_PROGRESS,
        )

        val updated = dao.observeById(row.id).first()!!
        assertEquals(0.42f, updated.progress, 0.001f)
        assertEquals("Episode 7", updated.currentUnitTitle)
        // A local progress write always leaves the row queued for upload.
        assertEquals(SyncState.PENDING_UPLOAD, updated.syncState)
    }

    @Test
    fun inProgressShelfExcludesUnstartedEntries() = runTest {
        val untouched = entry(sourceKey = "1")
        val started = entry(sourceKey = "2")
        dao.upsert(untouched)
        dao.upsert(started)
        dao.updateProgress(started.id, 0.3f, 1, "Ep 1", 0L, EntryStatus.IN_PROGRESS)

        val shelf = dao.observeInProgress().first()
        assertEquals(listOf(started.id), shelf.map { it.id })
    }

    @Test
    fun softDeleteHidesRowFromReads() = runTest {
        val row = entry()
        dao.upsert(row)
        dao.softDelete(row.id)

        assertTrue(dao.observeLibrary().first().isEmpty())
        // The row survives so the tombstone can be pushed to Supabase.
        assertEquals(row.id, dao.findById(row.id)?.id)
    }

    @Test
    fun unitsAreOrderedAndCascadeDeleted() = runTest {
        val row = entry()
        dao.upsert(row)
        dao.upsertUnits(
            listOf(
                LibraryUnitEntity(entryId = row.id, unitIndex = 2, title = "Two"),
                LibraryUnitEntity(entryId = row.id, unitIndex = 1, title = "One"),
            ),
        )

        assertEquals(listOf("One", "Two"), dao.observeUnits(row.id).first().map { it.title })

        // Soft delete keeps the row (and its units) until the server confirms.
        dao.softDelete(row.id)
        assertEquals(2, dao.observeUnits(row.id).first().size)

        // Purging the confirmed tombstone cascades to the units.
        dao.purgeTombstone(row.id)
        assertTrue(dao.observeUnits(row.id).first().isEmpty())
        assertNull(dao.findById(row.id))
    }

    @Test
    fun genresRoundTripThroughConverter() = runTest {
        val row = entry().copy(genres = listOf("Action", "Sci-Fi", "Music"))
        dao.upsert(row)

        val stored = dao.observeById(row.id).first()!!
        assertEquals(listOf("Action", "Sci-Fi", "Music"), stored.genres)
    }

    @Test
    fun remoteIdIsStableAcrossUpdates() = runTest {
        val row = entry()
        dao.upsert(row)
        dao.upsert(row.copy(title = "Updated"))

        assertEquals(row.remoteId, dao.observeById(row.id).first()!!.remoteId)
    }

    @Test
    fun kitsuLookupFindsEntryByKitsuId() = runTest {
        dao.upsert(entry().copy(kitsuId = "12345"))

        assertEquals("12345", dao.findByKitsuId("12345")?.kitsuId)
        assertNull(dao.findByKitsuId("99999"))
    }
}