package com.auris.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A user-created playlist of music tracks.
 *
 * ### Ownership
 * [userId] mirrors `library_entries`: the owning account id, or null for a device-local
 * playlist. Playlists are never synced to Supabase, so a null owner is a legitimate,
 * visible state (signed-out use), not the quarantine marker it is for library rows.
 */
@Entity(
    tableName = "playlists",
    indices = [Index("user_id"), Index("updated_at")],
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "user_id")
    val userId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * One track inside a [PlaylistEntity].
 *
 * ### Identity
 * [trackId] is the YouTube video id — the same value as `MusicRow.id`. It is deliberately
 * never a stream URL: stream URLs are session-bound and expire, while the video id resolves
 * fresh on every play.
 *
 * ### Ordering
 * [position] is 0-based, matching the array index the old SharedPreferences store used, so
 * the one-time import keeps the exact user-visible order. The unique (playlistId, trackId)
 * index enforces the "no duplicates" rule the old store upheld with `distinctBy`.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlist_id"),
        Index(value = ["playlist_id", "track_id"], unique = true),
    ],
)
data class PlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,

    @ColumnInfo(name = "position")
    val position: Int,

    @ColumnInfo(name = "track_id")
    val trackId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String? = null,

    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
