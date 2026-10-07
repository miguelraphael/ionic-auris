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
    }

    @After
    fun tearDown() = db.close()

    private fun entry(
        mediaType: MediaType = MediaType.ANIME,
        sourceKey: String = "1",
        title: String = "Test",
    ) = LibraryEntryEntity(
        mediaType = mediaType,
        sourceId = if (mediaType == MediaType.MUSIC) "innertube" else "example.org",
        sourceKey = sourceKey,
        title = title,
    )

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