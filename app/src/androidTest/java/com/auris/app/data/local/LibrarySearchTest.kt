package com.auris.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search behaviour, verified on a real device/emulator.
 *
 * This lives in `androidTest` rather than the Robolectric suite on purpose: Robolectric's
 * bundled SQLite returns no rows for `instr('Frieren', 'rien')` and for multi-character
 * `LIKE` patterns, so pattern matching cannot be asserted under Robolectric at all.
 * On a real Android runtime SQLite is spec-compliant and these cases are meaningful.
 *
 * Run with `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class LibrarySearchTest {

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

    private fun entry(title: String, key: String, author: String? = null) =
        LibraryEntryEntity(
            mediaType = MediaType.ANIME,
            sourceId = "example.org",
            sourceKey = key,
            title = title,
            author = author,
        )

    @Test
    fun matchesTitleSubstring() = runBlocking {
        dao.upsert(entry("Frieren: Beyond Journey's End", key = "1"))
        dao.upsert(entry("Bleach", key = "2"))

        assertEquals(1, dao.search("ier").first().size)
    }

    @Test
    fun matchesAuthorSubstring() = runBlocking {
        dao.upsert(entry("Frieren", key = "1", author = "Kanehito Yamada"))
        dao.upsert(entry("Bleach", key = "2", author = "Tite Kubo"))

        assertEquals(1, dao.search("Tite").first().size)
    }

    @Test
    fun matchingIsCaseInsensitive() = runBlocking {
        dao.upsert(entry("Frieren", key = "1", author = "Kanehito Yamada"))

        assertEquals(1, dao.search("IER").first().size)
        assertEquals(1, dao.search("yamada").first().size)
    }

    @Test
    fun emptyQueryReturnsEverything() = runBlocking {
        dao.upsert(entry("Frieren", key = "1"))
        dao.upsert(entry("Bleach", key = "2"))

        assertEquals(2, dao.search("").first().size)
    }

    @Test
    fun excludesSoftDeletedRows() = runBlocking {
        val row = entry("Frieren", key = "1")
        dao.upsert(row)
        dao.softDelete(row.id)

        assertEquals(0, dao.search("erien").first().size)
    }

    @Test
    fun respectsResultLimit() = runBlocking {
        repeat(5) { index ->
            dao.upsert(entry("Frieren $index", key = "key-$index"))
        }

        assertEquals(2, dao.search("ier", limit = 2).first().size)
    }
}