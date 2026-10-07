package com.auris.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history.
 *
 * Migrations are explicit and never destructive. The library table holds user data that
 * exists nowhere else until Supabase has seen it, so `fallbackToDestructiveMigration` would
 * mean deleting a user's library to recover from a version mismatch.
 */
object Migrations {

    /** v1 -> v2: adds the installed-extension registry. */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `installed_extensions` (
                    `package_name` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `version_name` TEXT NOT NULL,
                    `version_code` INTEGER NOT NULL,
                    `apk_path` TEXT NOT NULL,
                    `signature_hash` TEXT NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `repo_url` TEXT,
                    `installed_at` INTEGER NOT NULL,
                    PRIMARY KEY(`package_name`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_installed_extensions_package_name` " +
                    "ON `installed_extensions` (`package_name`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_installed_extensions_enabled` " +
                    "ON `installed_extensions` (`enabled`)",
            )
        }
    }

    /**
     * v2 -> v3: Kitsu art and catalogue metadata for the media detail screen.
     *
     * ### `kitsu_id` does NOT need adding — it is already there
     * It was introduced in **v1** as a nullable `TEXT` column with an index, and is present in
     * both exported schemas. An `ALTER TABLE ... ADD COLUMN kitsu_id` here would fail with
     * `SQLiteException: duplicate column name`, and adding it as a differently-named duplicate
     * would leave two sources of truth for one value. Verified by reading
     * `app/schemas/…/1.json` and `2.json`, both of which declare 33 fields including
     * `kitsu_id` and `index_library_entries_kitsu_id`.
     *
     * So this migration adds only what the detail screen genuinely needs and v1/v2 lacked:
     *
     * - **`kitsu_cover_url`** — Kitsu serves two *different* images: `posterImage` at ~3:4 and
     *   `coverImage` at ~4.2:1. `artwork_url` already holds the poster. The wide cover is what
     *   drives the screen's artwork gradient, and overwriting `artwork_url` with it would put a
     *   4.2:1 image into a 3:4 grid cell and crop it badly everywhere the poster is shown.
     * - **`kitsu_original_title`** — the source-language title. The matcher needs it to
     *   re-identify a title later, and the detail screen shows it as a subtitle.
     * - **`kitsu_rating`** — Kitsu's community average, as a fraction (0.0–100.0). Null rather
     *   than 0 so "unrated" is distinguishable from "rated zero".
     *
     * ### Why this preserves data
     * Every statement is `ADD COLUMN`, which SQLite performs in place by rewriting the table
     * definition and appending rows. Existing columns, rows and indices are untouched, and all
     * three new columns are nullable so no row needs a default. Nothing here reads or rewrites
     * `library_entries` data.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Wide (4.2:1) artwork, distinct from the 3:4 `artwork_url` poster.
            db.execSQL(
                "ALTER TABLE `library_entries` " +
                    "ADD COLUMN `kitsu_cover_url` TEXT",
            )

            // Source-language title, e.g. 葬送のフリーレン.
            db.execSQL(
                "ALTER TABLE `library_entries` " +
                    "ADD COLUMN `kitsu_original_title` TEXT",
            )

            // Kitsu averageRating, 0.0-100.0. Null when unrated.
            db.execSQL(
                "ALTER TABLE `library_entries` " +
                    "ADD COLUMN `kitsu_rating` REAL",
            )
        }
    }

    /** Every migration, in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}