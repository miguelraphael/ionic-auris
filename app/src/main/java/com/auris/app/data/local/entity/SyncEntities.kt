package com.auris.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import kotlinx.serialization.Serializable

/**
 * An outbox row written in the same Room transaction as the change that caused it.
 *
 * The WorkManager worker drains this table when connectivity is available; nothing in the UI
 * path ever waits on the network. `dedupeKey` collapses bursts (e.g. scrubbing the player) into
 * a single upload.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index("sync_state"),
        Index("entity_id"),
        // At most one queued op per (entity, type).
        Index(value = ["entity_id", "sync_op", "dedupe_key"], unique = true),
    ],
)
@Serializable
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    /** UPSERT / DELETE / PUSH_PROGRESS — see `SyncOp`. */
    @ColumnInfo(name = "sync_op")
    val syncOp: String = SyncOp.UPSERT,

    /** Repeated writes for the same op on the same entity collapse into one. */
    @ColumnInfo(name = "dedupe_key")
    val dedupeKey: String = entityId,

    @ColumnInfo(name = "payload")
    val payload: String? = null,

    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    @ColumnInfo(name = "sync_state")
    val syncState: SyncState = SyncState.PENDING_UPLOAD,

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

object SyncOp {
    const val UPSERT = "UPSERT"
    const val DELETE = "DELETE"
    const val PUSH_PROGRESS = "PUSH_PROGRESS"
}

/**
 * Tracks the Supabase auth session so sync can be gated on a signed-in user without
 * making auth part of the UI's critical path.
 */
@Entity(tableName = "sync_sessions")
@Serializable
data class SyncSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "email")
    val email: String? = null,

    @ColumnInfo(name = "access_token")
    val accessToken: String? = null,

    @ColumnInfo(name = "refresh_token")
    val refreshToken: String? = null,

    @ColumnInfo(name = "last_pulled_at")
    val lastPulledAt: Long? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)