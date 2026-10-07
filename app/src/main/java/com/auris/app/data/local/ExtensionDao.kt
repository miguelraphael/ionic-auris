package com.auris.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.auris.app.data.local.entity.ExtensionEntity
import kotlinx.coroutines.flow.Flow

/** Registered extensions. Source metadata is never cached here — see [ExtensionEntity]. */
@Dao
interface ExtensionDao {

    @Query("SELECT * FROM installed_extensions ORDER BY label ASC")
    fun observeInstalled(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM installed_extensions WHERE enabled = 1 ORDER BY label ASC")
    fun observeEnabled(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM installed_extensions WHERE enabled = 1")
    suspend fun enabledNow(): List<ExtensionEntity>

    @Query("SELECT * FROM installed_extensions WHERE package_name = :packageName")
    suspend fun find(packageName: String): ExtensionEntity?

    @Upsert
    suspend fun upsert(extension: ExtensionEntity)

    @Upsert
    suspend fun upsertAll(extensions: List<ExtensionEntity>)

    @Query("UPDATE installed_extensions SET enabled = :enabled WHERE package_name = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)

    @Query("DELETE FROM installed_extensions WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT COUNT(*) FROM installed_extensions WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>
}