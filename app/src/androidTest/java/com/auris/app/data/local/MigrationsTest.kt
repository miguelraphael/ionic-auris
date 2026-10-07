package com.auris.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests.
 *
 * These run on a device because Room's `MigrationTestHelper` opens a real SQLite file through
 * the Android SQLite implementation. Robolectric's bundled SQLite is close enough for the
 * query layer but is not the thing that executes a migration, so asserting one there would
 * test the wrong engine.
 *
 * The point of each test is the same: **existing rows survive**. A migration that drops or
 * rewrites a user's library is worse than one that fails, because the data is not recoverable
 * from anywhere until Supabase has seen it.
 */
@RunWith(AndroidJUnit4::class)
class MigrationsTest {

    /**
     * Registered as a rule, not just constructed.
     *
     * [MigrationTestHelper] implements `TestRule`: it opens the database *before* the test and
     * validates its schema *after*. Built as a plain field it is never run, and Room rejects it
     * with "You cannot register a database to be closed before the test starts".
     *
     * `emptyList()` for the auto-migration specs is deliberate — the whole point of these tests
     * is that the hand-written migrations in [Migrations] are complete on their own.
     */
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AurisDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun databaseAt(version: Int): SupportSQLiteDatabase =
        helper.createDatabase(AurisDatabase.NAME, version)

    // ---- v2 -> v3 -----------------------------------------------------------

    @Test
    fun migrates2To3AddingKitsuArtworkColumns() {
        val db = databaseAt(2)

        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, title, genres, status,
                progress, total_units, current_unit_index, score, is_favorite, categories,
                last_position_ms, is_deleted, sync_state, created_at, updated_at, last_activity_at
            ) VALUES (
                'entry-1', 'remote-1', 'ANIME', '1000', '/anime/alpha', 'Alpha Series',
                '["Action"]', 'ONGOING', 0.0, 12, 1, 0, 0, '[]', 0, 0, 'LOCAL_ONLY',
                1000, 1000, 1000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, kitsu_id, title, genres,
                status, progress, total_units, current_unit_index, score, is_favorite,
                categories, last_position_ms, is_deleted, sync_state, created_at, updated_at,
                last_activity_at
            ) VALUES (
                'entry-2', 'remote-2', 'MANGA', '2000', '/manga/bravo', NULL, 'Bravo Chapter',
                '["Drama"]', 'FINISHED', 0.5, 50, 25, 8, 1, '["Reading"]', 0, 0, 'SYNCED',
                2000, 2000, 2000
            )
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME,
            3,
            true,
            Migrations.MIGRATION_2_3,
        )

        // `runMigrationsAndValidate(validateDroppedTables = true)` re-reads the exported v3
        // schema and compares it to what the migration produced, so a column added to the
        // entity but not to the migration fails here.
        migrated.query("SELECT COUNT(*) FROM library_entries").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0))
        }
    }

    @Test
    fun existingRowsAndValuesSurviveMigration() {
        val db = databaseAt(2)
        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, kitsu_id, kitsu_slug, title,
                author, description, genres, status, progress, total_units, current_unit_index,
                score, is_favorite, categories, last_position_ms, is_deleted, sync_state,
                created_at, updated_at, last_activity_at
            ) VALUES (
                'keep-me', 'remote-9', 'ANIME', '3000', '/anime/keep', '777', 'sousou-no-frieren',
                'Sousou no Frieren', 'Author One', 'A synopsis.', '["Action","Drama"]',
                'IN_PROGRESS', 0.75, 28, 7, 9, 1, '["Favourite"]', 123456, 0, 'SYNCED',
                111, 222, 333
            )
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME, 3, true, Migrations.MIGRATION_2_3,
        )

        migrated.query(
            "SELECT id, kitsu_id, kitsu_slug, title, author, description, total_units, " +
                "current_unit_index, score, is_favorite, created_at, updated_at, " +
                "last_activity_at, sync_state FROM library_entries WHERE id = 'keep-me'",
        ).use { c ->
            assertTrue("row disappeared during migration", c.moveToFirst())

            assertEquals("keep-me", c.getString(0))
            // The pre-existing Kitsu columns must be untouched by the migration.
            assertEquals("777", c.getString(1))
            assertEquals("sousou-no-frieren", c.getString(2))
            assertEquals("Sousou no Frieren", c.getString(3))
            assertEquals("Author One", c.getString(4))
            assertEquals("A synopsis.", c.getString(5))
            assertEquals(28, c.getInt(6))
            assertEquals(7, c.getInt(7))
            assertEquals(9, c.getInt(8))
            assertEquals(1, c.getInt(9))
            assertEquals(111, c.getInt(10))
            assertEquals(222, c.getInt(11))
            assertEquals(333, c.getInt(12))
            assertEquals("SYNCED", c.getString(13))
        }
    }

    @Test
    fun newKitsuColumnsAreNullForPreexistingRows() {
        val db = databaseAt(2)
        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, title, genres, status,
                progress, total_units, current_unit_index, score, is_favorite, categories,
                last_position_ms, is_deleted, sync_state, created_at, updated_at, last_activity_at
            ) VALUES (
                'old', 'r', 'ANIME', '1', '/a', 'Old Entry', '[]', 'ONGOING', 0.0, 1, 0, 0, 0,
                '[]', 0, 0, 'LOCAL_ONLY', 1, 1, 1
            )
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME, 3, true, Migrations.MIGRATION_2_3,
        )

        migrated.query(
            "SELECT kitsu_cover_url, kitsu_original_title, kitsu_rating FROM library_entries " +
                "WHERE id = 'old'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            // Null, not empty string and not 0: an unidentified entry must stay unpopulated
            // rather than look identified with blank data.
            assertNull(c.getString(0))
            assertNull(c.getString(1))
            assertTrue(c.isNull(2))
        }
    }

    @Test
    fun newKitsuColumnsAcceptValuesAndAreIndependentOfThePoster() {
        val db = databaseAt(2)
        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, title, genres, status,
                progress, total_units, current_unit_index, score, is_favorite, categories,
                last_position_ms, is_deleted, sync_state, created_at, updated_at, last_activity_at
            ) VALUES (
                'x', 'r', 'ANIME', '1', '/a', 'X', '[]', 'ONGOING', 0.0, 1, 0, 0, 0, '[]',
                0, 0, 'LOCAL_ONLY', 1, 1, 1
            )
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME, 3, true, Migrations.MIGRATION_2_3,
        )

        migrated.execSQL(
            """
            UPDATE library_entries
               SET artwork_url = 'https://img.test/poster.jpg',
                   kitsu_cover_url = 'https://img.test/cover.jpg',
                   kitsu_original_title = '葬送のフリーレン',
                   kitsu_rating = 88.84
             WHERE id = 'x'
            """.trimIndent(),
        )

        migrated.query(
            "SELECT artwork_url, kitsu_cover_url, kitsu_original_title, kitsu_rating " +
                "FROM library_entries WHERE id = 'x'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            // The 3:4 poster and the 4.2:1 cover must coexist; collapsing them into one column
            // would crop whichever the grid shows.
            assertEquals("https://img.test/poster.jpg", c.getString(0))
            assertEquals("https://img.test/cover.jpg", c.getString(1))
            assertEquals("葬送のフリーレン", c.getString(2))
            assertEquals(88.84, c.getDouble(3), 0.001)
        }
    }

    @Test
    fun libraryUnitsSurviveMigration() {
        val db = databaseAt(2)
        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, title, genres, status,
                progress, total_units, current_unit_index, score, is_favorite, categories,
                last_position_ms, is_deleted, sync_state, created_at, updated_at, last_activity_at
            ) VALUES (
                'parent', 'r', 'ANIME', '1', '/a', 'Parent', '[]', 'ONGOING', 0.0, 2, 0, 0, 0,
                '[]', 0, 0, 'LOCAL_ONLY', 1, 1, 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO library_units (
                entry_id, unit_index, source_unit_id, title, is_watched, last_position_ms,
                bookmark, updated_at
            ) VALUES ('parent', 0, 'u1', 'Episode 1', 1, 5000, 0, 1)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO library_units (
                entry_id, unit_index, source_unit_id, title, is_watched, last_position_ms,
                bookmark, updated_at
            ) VALUES ('parent', 1, 'u2', 'Episode 2', 0, 0, 0, 1)
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME, 3, true, Migrations.MIGRATION_2_3,
        )

        migrated.query(
            "SELECT COUNT(*) FROM library_units WHERE entry_id = 'parent'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("episode rows lost", 2, c.getInt(0))
        }
    }

    @Test
    fun installedExtensionsSurviveMigration() {
        val db = databaseAt(2)
        db.execSQL(
            """
            INSERT INTO installed_extensions (
                package_name, label, version_name, version_code, apk_path, signature_hash,
                enabled, repo_url, installed_at
            ) VALUES (
                'com.auris.mock', 'Mock', '1.0', 1, '/tmp/mock.apk', 'sha256', 1,
                'https://repo.test', 500
            )
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME, 3, true, Migrations.MIGRATION_2_3,
        )

        migrated.query("SELECT label, signature_hash FROM installed_extensions").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Mock", c.getString(0))
            assertEquals("sha256", c.getString(1))
        }
    }

    @Test
    fun syncOutboxSurvivesMigration() {
        // The outbox is pending work. Losing it means a change silently never reaches Supabase,
        // which is a data-loss bug that would not show up until the user noticed a missing sync.
        val db = databaseAt(2)
        db.execSQL(
            """
            INSERT INTO library_entries (
                id, remote_id, media_type, source_id, source_key, title, genres, status,
                progress, total_units, current_unit_index, score, is_favorite, categories,
                last_position_ms, is_deleted, sync_state, created_at, updated_at, last_activity_at
            ) VALUES (
                'p', 'r', 'ANIME', '1', '/a', 'P', '[]', 'ONGOING', 0.0, 1, 0, 0, 0, '[]',
                0, 0, 'PENDING', 1, 1, 1
            )
            """.trimIndent(),
        )
        helper.closeWhenFinished(db)

        val migrated = helper.runMigrationsAndValidate(
            AurisDatabase.NAME, 3, true, Migrations.MIGRATION_2_3,
        )

        assertNotNull(migrated.query("SELECT COUNT(*) FROM sync_outbox").use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        })
    }

    @Test
    fun migrationChainIsOrderedAndComplete() {
        // A gap in the chain means an installed database cannot open: Room looks for a path
        // from its version to the current one and finds none.
        val versions = Migrations.ALL.map { it.startVersion to it.endVersion }

        assertEquals(listOf(1 to 2, 2 to 3), versions)
        // Contiguous from 1 with no gaps.
        versions.zipWithNext().forEach { (first, second) ->
            assertEquals(
                "gap between $first and $second",
                first.second,
                second.first,
            )
        }
    }
}