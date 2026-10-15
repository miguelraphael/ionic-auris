package com.auris.app.data.remote

import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.LibraryCategory
import com.auris.app.core.model.MediaType
import com.auris.app.core.model.SyncState
import com.auris.app.data.local.entity.LibraryEntryEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The PostgREST row shape for `library_entries`.
 *
 * A dedicated DTO rather than a `Map`: a typo in a column name becomes a compile error
 * instead of a silently-null field, and the payload doubles as the outbox serialization
 * format for [LibraryEntryEntity].
 *
 * ### Wire types must match Postgres, not Room
 * Timestamps cross as ISO-8601 strings (`timestamptz`), never epoch millis:
 * a JSON number into a `timestamptz` column is a 400 on every upsert. Columns
 * that exist only locally (`localArtworkPath`, sync bookkeeping) are absent
 * here on purpose — the server can never return them, and sending unknown
 * keys fails the write.
 */
@Serializable
data class LibraryEntryRow(
    @SerialName("id") val id: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("media_type") val mediaType: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_key") val sourceKey: String,
    @SerialName("remote_media_id") val remoteMediaId: String? = null,
    @SerialName("kitsu_id") val kitsuId: String? = null,
    @SerialName("kitsu_slug") val kitsuSlug: String? = null,
    @SerialName("mal_id") val malId: Long? = null,
    @SerialName("title") val title: String,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("author") val author: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("genres") val genres: List<String> = emptyList(),
    @SerialName("release_year") val releaseYear: Int? = null,
    @SerialName("status") val status: String = EntryStatus.PLANNED.name,
    @SerialName("progress") val progress: Float = 0f,
    @SerialName("total_units") val totalUnits: Int = 0,
    @SerialName("current_unit_index") val currentUnitIndex: Int = 0,
    @SerialName("current_unit_title") val currentUnitTitle: String? = null,
    @SerialName("score") val score: Int = 0,
    @SerialName("is_favorite") val isFavorite: Boolean = false,
    @SerialName("categories") val categories: List<String> = emptyList(),
    @SerialName("last_position_ms") val lastPositionMs: Long = 0L,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * Builds the row to upsert.
 *
 * `user_id` is always supplied by the worker from the stored session rather than the
 * entity: the device may hold rows queued under a previous account, and letting a stale
 * `userId` column win would write them under the wrong owner.
 */
fun LibraryEntryEntity.toRow(userId: String, now: InstantMillis): LibraryEntryRow =
    LibraryEntryRow(
        id = remoteId,
        userId = userId,
        mediaType = mediaType.name,
        sourceId = sourceId,
        sourceKey = sourceKey,
        remoteMediaId = remoteMediaId,
        kitsuId = kitsuId,
        kitsuSlug = kitsuSlug,
        malId = malId,
        title = title,
        subtitle = subtitle,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        genres = genres,
        releaseYear = releaseYear,
        status = status.name,
        progress = progress,
        totalUnits = totalUnits,
        currentUnitIndex = currentUnitIndex,
        currentUnitTitle = currentUnitTitle,
        score = score,
        isFavorite = isFavorite,
        categories = categories.map { it.name },
        lastPositionMs = lastPositionMs,
        createdAt = iso8601(createdAt),
        updatedAt = iso8601(now),
    )

/**
 * Row -> entity. Rows pulled from the server are marked [SyncState.SYNCED] so the pull does
 * not immediately re-queue them for upload.
 */
fun LibraryEntryRow.toEntity(): LibraryEntryEntity = LibraryEntryEntity(
    id = id,
    remoteId = id,
    userId = userId,
    mediaType = MediaType.fromDb(mediaType),
    sourceId = sourceId,
    sourceKey = sourceKey,
    remoteMediaId = remoteMediaId,
    kitsuId = kitsuId,
    kitsuSlug = kitsuSlug,
    malId = malId,
    artworkUrl = artworkUrl,
    // Local-only path: the server never returns one, so a pulled row starts unset.
    localArtworkPath = null,
    title = title,
    subtitle = subtitle,
    author = author,
    description = description,
    genres = genres,
    releaseYear = releaseYear,
    status = runCatching { EntryStatus.valueOf(status) }.getOrDefault(EntryStatus.PLANNED),
    progress = progress,
    totalUnits = totalUnits,
    currentUnitIndex = currentUnitIndex,
    currentUnitTitle = currentUnitTitle,
    score = score,
    isFavorite = isFavorite,
    categories = categories.mapNotNull {
        runCatching { LibraryCategory.valueOf(it) }.getOrNull()
    },
    lastPositionMs = lastPositionMs,
    syncState = SyncState.SYNCED,
    createdAt = parseInstant(createdAt),
    updatedAt = parseInstant(updatedAt),
    remoteUpdatedAt = parseInstant(updatedAt),
)

/** Epoch millis from an ISO-8601 `timestamptz`, or now when absent/unparseable. */
private fun parseInstant(value: String?): Long =
    runCatching {
        java.time.Instant.parse(value).toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())

private fun iso8601(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis).toString()

/** Epoch-millis alias, so call sites don't need a java.time import. */
typealias InstantMillis = Long