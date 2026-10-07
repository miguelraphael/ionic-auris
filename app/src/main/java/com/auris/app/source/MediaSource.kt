package com.auris.app.source

import com.auris.app.core.model.MediaType
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Contract Auris expects of every catalogue source, whether it arrived as an Aniyomi
 * extension APK or as a first-party client.
 *
 * ### Why this exists
 * Extensions loaded by the extension manager are third-party APKs compiled against
 * Aniyomi's `eu.kanade.tachiyomi.source.HttpSource` / `AnimeHttpSource` interfaces. They
 * cannot implement our interfaces directly, so an adapter wraps each one and presents it
 * as a [MediaSource]. Callers above this layer never branch on "extension vs
 * built-in" — that keeps catalog parsing, caching and UI code uniform.
 */
interface MediaSource {
    val id: String
    val name: String
    val mediaType: MediaType
    val baseUrl: String

    /** Source-specific config persisted per install (headers, filters, per-user state). */
    suspend fun configuration(): Map<String, String>

    suspend fun updateConfiguration(values: Map<String, String>)

    // ---- Catalogue -----------------------------------------------------------
    //
    // `page` is 1-based, matching Aniyomi's convention so pagination indices transfer
    // between the extension and the UI without an off-by-one layer.

    suspend fun popular(page: Int): SourcePage

    suspend fun latest(page: Int): SourcePage

    suspend fun search(page: Int, query: String, filters: FilterList): SourcePage

    /** Fills in the fields the catalogue listing could not supply. */
    suspend fun details(entry: SourceItem): SourceItem

    // ---- Content -------------------------------------------------------------

    /** Episodes (anime) or chapters (manga). */
    suspend fun units(entry: SourceItem): List<SourceUnit>

    /** Playable targets for a unit. Empty for manga, which exposes pages instead. */
    suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget>
}

/** A paginated slice of a source's catalogue or search results. */
data class SourcePage(
    val items: List<SourceItem>,
    val hasNextPage: Boolean,
)

data class SourceItem(
    val url: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String? = null,
    /** Comic/manga: total chapter count when the source advertises it. */
    val episodeCount: Int? = null,
    /** Anime: recognised release year, used to seed the Kitsu lookup. */
    val year: Int? = null,
)

/** An episode (anime) or chapter (manga) as offered by a source. */
data class SourceUnit(
    val url: String,
    val index: Int,
    val title: String,
    val thumbnailUrl: String? = null,
    /** Broadcast timestamp in epoch millis, or 0 when unknown. */
    val uploadTimestamp: Long = 0L,
)

/** Resolved playback/reading target. Stream URLs from HTTP sources are often ephemeral. */
data class StreamTarget(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    /** Adaptive DASH manifest when the source offers one. */
    val isAdaptive: Boolean = false,
)

/** A manga chapter's page list. */
data class PageList(
    val pages: List<String>,
    val headers: Map<String, String> = emptyMap(),
)