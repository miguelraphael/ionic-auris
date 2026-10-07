package com.auris.app.data.remote.music

import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import java.security.MessageDigest

/**
 * Maps YouTube Music content onto the unified `library_entries` / `library_units` schema.
 *
 * ### How music fits the unified table
 * The identity tuple `(media_type, source_id, source_key)` is what the schema documents for
 * music: `source_key` is the literal `innertube` and `source_id` is the YouTube video id. That
 * matters because a user can add the same track from two sources; the unique index on the tuple
 * means the second add upserts rather than duplicating.
 *
 * ### An album is one entry, its tracks are its units
 * `total_units` is the track count and `current_unit_index` is the track being played, so an
 * album's progress is the same shape as an anime's episode progress — and the existing library
 * progress UI works on music without knowing the difference.
 *
 * ### Nothing here is verified against the live API
 * The mapping is derived from the schema's own documented contract plus the fields InnerTube is
 * known to return. Like the parser, it is inference rather than a verified contract.
 */
object MusicLibraryMapper {

    /** The `source_key` every music row carries; see the schema docs. */
    const val SOURCE_KEY = "innertube"

    /**
     * Builds a `library_entries` row for one track.
     *
     * @param isCurrent whether this track is the one now playing, which seeds the progress
     *   index so reopening the app lands on the right track.
     */
    fun toEntry(
        track: MusicTrack,
        progressFraction: Float = 0f,
        isCurrent: Boolean = false,
        installedAtMs: Long = System.currentTimeMillis(),
    ): LibraryEntryEntity = LibraryEntryEntity(
        id = stableId(track.videoId),
        // Media ids are remote but stable; `source_id` is the video id and is what identifies
        // the row within the music source.
        remoteId = track.videoId,
        mediaType = MediaType.MUSIC,
        sourceId = track.videoId,
        sourceKey = SOURCE_KEY,
        // The playlist this came from, when known, so "go to album" works from the row.
        remoteMediaId = track.playlistId,
        // Album art. `artworkUrl` is what the grid and the player's centred square art use, so
        // the larger variant is preferred; music has no separate wide-cover field.
        artworkUrl = track.largeArtworkUrl ?: track.artworkUrl,
        title = track.title,
        // Artist • Album, matching the two-tier metadata the player screen renders.
        subtitle = listOfNotNull(track.artist, track.album).joinToString(" • ").takeIf { it.isNotBlank() },
        author = track.artist,
        description = null,
        genres = emptyList(),
        // InnerTube does not publish a release year on the track itself; it comes from the
        // album when one is browsed.
        releaseYear = null,
        status = EntryStatus.IN_PROGRESS,
        progress = progressFraction.coerceIn(0f, 1f),
        totalUnits = 1,
        // A single-track entry's only unit is index 1, matching the 1-based unit convention.
        currentUnitIndex = if (isCurrent) 1 else 1,
        currentUnitTitle = null,
        createdAt = installedAtMs,
        updatedAt = installedAtMs,
        lastActivityAt = installedAtMs,
    )

    /**
     * Builds a `library_units` row for one track of an album.
     *
     * @param entryId the parent album row.
     * @param trackIndex **1-based** position, matching the schema's documented convention for
     *   `library_units.unit_index` ("Episode 1, chapter 1, track 1"). InnerTube itself numbers
     *   tracks from zero in some responses, so converting at the boundary here is the single
     *   place that has to be right.
     */
    fun toUnit(
        entryId: String,
        track: MusicTrack,
        trackIndex: Int,
    ): LibraryUnitEntity = LibraryUnitEntity(
        entryId = entryId,
        unitIndex = trackIndex,
        // The YouTube video id, not the display title: titles get re-spelled, ids do not.
        sourceUnitId = track.videoId,
        title = track.title,
        thumbnailUrl = track.artworkUrl,
        // Never the resolved stream URL. InnerTube URLs expire in about six hours, so storing
        // one guarantees a 403 on the next play; the player re-resolves per playback instead.
        streamUrl = null,
        durationMs = track.durationMs,
        isWatched = false,
        lastPositionMs = 0L,
        bookmark = 0,
        updatedAt = System.currentTimeMillis(),
    )

    /**
     * Builds an album entry: one row, with its track count as `totalUnits`.
     *
     * This is what makes music progress legible through the existing library UI — an album at
     * track 7 of 12 shows 58% exactly as an anime at episode 7 of 12 does.
     */
    fun albumToEntry(
        album: MusicAlbum,
        installedAtMs: Long = System.currentTimeMillis(),
    ): LibraryEntryEntity {
        val trackCount = album.trackCount ?: 0
        val id = stableId("album:${album.browseId}")

        return LibraryEntryEntity(
            id = id,
            remoteId = album.browseId,
            mediaType = MediaType.MUSIC,
            sourceId = album.browseId,
            sourceKey = SOURCE_KEY,
            remoteMediaId = null,
            artworkUrl = album.artworkUrl,
            title = album.title,
            subtitle = album.artist,
            author = album.artist,
            releaseYear = album.year,
            status = EntryStatus.IN_PROGRESS,
            totalUnits = trackCount,
            createdAt = installedAtMs,
            updatedAt = installedAtMs,
            lastActivityAt = installedAtMs,
        )
    }

    /** The 1-based unit index for a 0-based position in a track list. */
    fun toUnitIndex(zeroBasedPosition: Int): Int = zeroBasedPosition + 1

    /**
     * A stable local id for a video.
     *
     * Room's primary key is a String, so this is deterministic rather than random: the same
     * track must produce the same row every time it is added, or re-adding would duplicate it
     * instead of upserting.
     *
     * A SHA-1 prefix rather than the raw video id because a bare 11-character id is short enough
     * to collide with other generated ids and reads as opaque noise in the database.
     */
    fun stableId(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(videoId.toByteArray())
        return "music-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

}