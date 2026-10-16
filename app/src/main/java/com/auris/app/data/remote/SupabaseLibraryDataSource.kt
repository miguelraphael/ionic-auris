package com.auris.app.data.remote

import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.sync.PermanentSyncException
import com.auris.app.data.sync.TransientSyncException

/**
 * Supabase-backed library transport.
 *
 * ### Schema this maps to
 * ```sql
 * create type media_type as enum ('ANIME','MANGA','MUSIC');
 * create type entry_status as enum ('PLANNED','IN_PROGRESS','COMPLETED','DROPPED','ON_HOLD');
 *
 * create table library_entries (
 *   id            uuid primary key,           -- == entity.remoteId
 *   user_id       uuid not null references auth.users on delete cascade,
 *   media_type    media_type not null,
 *   source_id     text not null,               -- Aniyomi pkg or 'innertube'
 *   source_key    text not null,               -- source key or YT video id
 *   remote_media_id text,
 *   kitsu_id      text,
 *   kitsu_slug    text,
 *   mal_id        bigint,
 *   title         text not null,
 *   artwork_url   text,
 *   subtitle      text,
 *   author        text,
 *   description   text,
 *   genres        text[] not null default '{}',
 *   release_year  int,
 *   status        entry_status not null default 'PLANNED',
 *   progress      real not null default 0,
 *   total_units   int not null default 0,
 *   current_unit_index int not null default 0,
 *   current_unit_title text,
 *   score         int not null default 0,
 *   is_favorite   boolean not null default false,
 *   categories    text[] not null default '{}',
 *   last_position_ms bigint not null default 0,
 *   created_at    timestamptz not null default now(),
 *   updated_at    timestamptz not null default now()
 * );
 * create unique index on library_entries (user_id, media_type, source_id, source_key);
 * create index on library_entries (user_id, updated_at desc);
 * ```
 *
 * Note the unique index includes `user_id`: local identity is per-user, so the same source
 * media can exist independently in two accounts on one device.
 */
interface SupabaseLibraryDataSource {

    suspend fun upsertEntry(userId: String, entry: LibraryEntryEntity)

    suspend fun deleteEntry(userId: String, entryId: String)

    /**
     * Deletes every row owned by [userId]. Used by account deletion, where
     * per-row deletes would be a round trip per title for no benefit.
     */
    suspend fun deleteAllEntries(userId: String)

    /**
     * @param since high-water mark in epoch millis, or null for a full pull.
     * @return remote rows, newest first.
     */
    suspend fun fetchEntries(userId: String, since: Long?): List<LibraryEntryEntity>
}