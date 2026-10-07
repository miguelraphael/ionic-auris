package com.auris.app.core.model

/**
 * The three content pillars Auris unifies. Persisted by [name] in Room and mirrored
 * in Supabase as a Postgres enum, so the two must stay in lockstep.
 */
enum class MediaType {
    ANIME,
    MANGA,
    MUSIC;

    companion object {
        fun fromDb(value: String?): MediaType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MUSIC
    }
}

/**
 * How far a user has moved through an entry. Drives the progress bar in the UI and the
 * "continue watching / reading / listening" shelf.
 */
enum class EntryStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    DROPPED,
    ON_HOLD,
}

/**
 * Library bucketing used by the unified favorites grid. A single entry can belong to
 * several lists at once (e.g. a Kitsu anime that is also in a source extension).
 */
enum class LibraryCategory {
    FAVORITE,
    ANIMELIST,
    MANGALIST,
    PLAYLIST,
    RECENT,
    DOWNLOADED,
}

/** Local-only sync bookkeeping. Never leaves the device. */
enum class SyncState {
    /** Never touched by Supabase. */
    LOCAL_ONLY,
    /** Written locally, push worker has not confirmed it yet. */
    PENDING_UPLOAD,
    /** Confirmed by the server; [com.auris.app.data.local.entity.LibraryEntryEntity.remoteUpdatedAt] is set. */
    SYNCED,
    /** Server rejected or the row disappeared remotely. */
    CONFLICT,
}