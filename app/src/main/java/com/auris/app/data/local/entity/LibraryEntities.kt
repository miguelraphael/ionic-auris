package com.auris.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.LibraryCategory
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import kotlinx.serialization.Serializable

/**
 * The unified favorites row. One table serves anime, manga and music so the library grid,
 * the "continue" shelf and the Supabase sync payload can all be single-query.
 *
 * ### Identity
 * A row is identified by the tuple ([mediaType], [sourceId], [sourceKey]):
 *  - [MediaType.MUSIC] → `sourceKey` is `innertube`, [sourceId] is the YouTube video id.
 *  - [MediaType.ANIME] / [MediaType.MANGA] → [sourceId] is the Aniyomi source id/slug.
 *
 * [remoteId] is the stable, source-agnostic UUID that Supabase mirrors. It is generated on
 * first insert and never changes, so offline-created rows upsert cleanly on reconnect.
 */
@Entity(
    tableName = "library_entries",
    indices = [
        // Enforces one row per (type, source, sourceKey). Upserts use this conflict target.
        Index(value = ["media_type", "source_id", "source_key"], unique = true),
        Index("media_type"),
        Index("sync_state"),
        Index("last_activity_at"),
        Index("kitsu_id"),
    ],
)
@Serializable
data class LibraryEntryEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "id")
    val id: String = java.util.UUID.randomUUID().toString(),

    /**
     * Same value as [id]. Kept as its own column so it can be handed straight to Supabase
     * as the Postgres `uuid` primary key without a client-side re-key.
     */
    @ColumnInfo(name = "remote_id")
    val remoteId: String = id,

    @ColumnInfo(name = "user_id")
    val userId: String? = null,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    // ---- Identity / provenance -------------------------------------------------

    /** Aniyomi source package name, or `innertube` for music. */
    @ColumnInfo(name = "source_id")
    val sourceId: String,

    /** Aniyomi source key (multi-variant sources) or the YouTube video id. */
    @ColumnInfo(name = "source_key")
    val sourceKey: String,

    /** Kitsu slug for anime/manga; album id for music; episode-scoped id for episodes. */
    @ColumnInfo(name = "remote_media_id")
    val remoteMediaId: String? = null,

    @ColumnInfo(name = "kitsu_id")
    val kitsuId: String? = null,

    @ColumnInfo(name = "kitsu_slug")
    val kitsuSlug: String? = null,

    /**
     * Wide (roughly 4.2:1) Kitsu artwork, distinct from the 3:4 [artworkUrl] poster.
     *
     * Kitsu serves both, and they are not interchangeable: this one drives the media detail
     * screen's background gradient. Storing a wide image in [artworkUrl] instead would crop it
     * badly in every grid cell.
     */
    @ColumnInfo(name = "kitsu_cover_url")
    val kitsuCoverUrl: String? = null,

    /**
     * Source-language title, e.g. 葬送のフリーレン.
     *
     * Kept so the title can be re-identified later: `kitsuId` is the stable reference, but the
     * matcher needs the original spelling to re-run a search when Kitsu's data changes.
     */
    @ColumnInfo(name = "kitsu_original_title")
    val kitsuOriginalTitle: String? = null,

    /** Kitsu's community average rating, 0.0-100.0. Null when unrated. */
    @ColumnInfo(name = "kitsu_rating")
    val kitsuRating: Double? = null,

    @ColumnInfo(name = "mal_id")
    val malId: Long? = null,

    /** Channel/album art URL. Local cache paths are stored separately in [localArtworkPath]. */
    @ColumnInfo(name = "artwork_url")
    val artworkUrl: String? = null,

    @ColumnInfo(name = "local_artwork_path")
    val localArtworkPath: String? = null,

    // ---- Display metadata ------------------------------------------------------

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String? = null,

    @ColumnInfo(name = "author")
    val author: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "genres")
    val genres: List<String> = emptyList(),

    @ColumnInfo(name = "release_year")
    val releaseYear: Int? = null,

    // ---- Progress --------------------------------------------------------------

    @ColumnInfo(name = "status")
    val status: EntryStatus = EntryStatus.PLANNED,

    @ColumnInfo(name = "progress")
    val progress: Float = 0f,

    @ColumnInfo(name = "total_units")
    val totalUnits: Int = 0,

    @ColumnInfo(name = "current_unit_index")
    val currentUnitIndex: Int = 0,

    @ColumnInfo(name = "current_unit_title")
    val currentUnitTitle: String? = null,

    @ColumnInfo(name = "score")
    val score: Int = 0,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "categories")
    val categories: List<LibraryCategory> = emptyList(),

    @ColumnInfo(name = "last_position_ms")
    val lastPositionMs: Long = 0L,

    // ---- Tracking / bookkeeping ------------------------------------------------

    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,

    @ColumnInfo(name = "sync_state")
    val syncState: SyncState = SyncState.LOCAL_ONLY,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_activity_at")
    val lastActivityAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "remote_updated_at")
    val remoteUpdatedAt: Long? = null,
)

/**
 * Episode / chapter / track as it appears inside a [LibraryEntryEntity].
 *
 * Present for anime ([MediaType.ANIME]) and manga ([MediaType.MANGA]); music uses
 * [MediaType.MUSIC] with `unitIndex` ordering tracks on an album or playlist.
 */
@Entity(
    tableName = "library_units",
    primaryKeys = ["entry_id", "unit_index"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("entry_id"),
        Index("is_watched"),
    ],
)
@Serializable
data class LibraryUnitEntity(
    @ColumnInfo(name = "entry_id")
    val entryId: String,

    /** 1-based ordering as shown to the user. Episode 1, chapter 1, track 1. */
    @ColumnInfo(name = "unit_index")
    val unitIndex: Int,

    @ColumnInfo(name = "source_unit_id")
    val sourceUnitId: String? = null,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    /** Direct stream/reader URL, resolved lazily by the source at playback time. */
    @ColumnInfo(name = "stream_url")
    val streamUrl: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    @ColumnInfo(name = "is_watched")
    val isWatched: Boolean = false,

    @ColumnInfo(name = "last_position_ms")
    val lastPositionMs: Long = 0L,

    @ColumnInfo(name = "bookmark")
    val bookmark: Int = 0,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)