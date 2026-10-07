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
import com.auris.app.data.local.entity.SyncOutboxEntity
import com.auris.app.data.local.entity.SyncSessionEntity

@Database(
    entities = [
        LibraryEntryEntity::class,
        LibraryUnitEntity::class,
        SyncOutboxEntity::class,
        SyncSessionEntity::class,
        ExtensionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AurisDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao
    abstract fun syncDao(): SyncDao
    abstract fun extensionDao(): ExtensionDao

    companion object {
        const val NAME = "auris.db"

        fun build(context: Context): AurisDatabase =
            Room.databaseBuilder(context.applicationContext, AurisDatabase::class.java, NAME)
                .addMigrations(*Migrations.ALL)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Required for our ON DELETE CASCADE from library_entries to library_units.
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                })
                // No fallbackToDestructiveMigration: the library is user data and a
                // destructive migration would silently wipe it. Ship explicit migrations.
                .build()
    }
}