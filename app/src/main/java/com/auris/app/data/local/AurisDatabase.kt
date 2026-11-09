package com.auris.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.auris.app.data.local.entity.ExtensionEntity
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.local.entity.PlaylistEntity
import com.auris.app.data.local.entity.PlaylistTrackEntity
import com.auris.app.data.local.entity.SyncOutboxEntity
import com.auris.app.data.local.entity.SyncSessionEntity

@Database(
    entities = [
        LibraryEntryEntity::class,
        LibraryUnitEntity::class,
        SyncOutboxEntity::class,
        SyncSessionEntity::class,
        ExtensionEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AurisDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao
    abstract fun syncDao(): SyncDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "auris.db"

        fun build(context: Context): AurisDatabase =
            Room.databaseBuilder(context.applicationContext, AurisDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Required for our ON DELETE CASCADE from library_entries to library_units.
                        db.execSQL("PRAGMA foreign_keys = ON")
                        db.execSQL("UPDATE sync_sessions SET access_token = NULL, refresh_token = NULL")
                    }
                })
                // No fallbackToDestructiveMigration: the library is user data and a
                // destructive migration would silently wipe it. Ship explicit migrations.
                .build()
    }
}