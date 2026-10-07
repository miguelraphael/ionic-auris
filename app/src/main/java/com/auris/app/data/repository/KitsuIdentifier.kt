package com.auris.app.data.repository

import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.remote.kitsu.KitsuDataSource
import com.auris.app.data.remote.kitsu.KitsuMapper
import com.auris.app.data.remote.kitsu.KitsuMetadata
import com.auris.app.data.remote.kitsu.KitsuMetadataMatch
import com.auris.app.data.remote.kitsu.KitsuMetadataMatcher
import android.util.Log
import java.util.LinkedHashMap

/**
 * Identifies extension results against Kitsu and writes what it finds onto library entries.
 *
 * ### Why this is not part of `upsert`
 * A Kitsu lookup is a network call that can be slow, rate-limited, or return nothing. Folding
 * it into [LibraryRepository.upsert] would put a round-trip on the critical path of every
 * library write, including offline ones, and turn a Kitsu outage into a save failure. So
 * identification is a separate, explicit, failure-tolerant step whose result is simply stored.
 *
 * [LibraryRepository.upsert] is passed in as a function rather than as the repository itself:
 * this class needs exactly one operation, and depending on one operation keeps the contract
 * testable without a mocking framework.
 *
 * ### Failures are silent by design
 * A failed lookup leaves `kitsuId` null and the entry otherwise untouched. Metadata is
 * enrichment: a title Kitsu has never heard of is still a usable library entry, and discarding
 * the extension's own title because an enrichment lookup failed would be far worse than having
 * no enrichment at all.
 *
 * ### Caching
 * Kitsu allows roughly 3 requests/second unauthenticated, and the same title gets looked up
 * every time a screen is opened. [cachedSearch] memoises per query for the lifetime of this
 * instance, so re-opening a detail screen costs nothing.
 */
class KitsuIdentifier(
    private val client: KitsuDataSource,
    /** Persists the enriched entry. Narrow so the write behaviour can be asserted directly. */
    private val write: suspend (LibraryEntryEntity) -> Unit,
    /** Locale whose Kitsu title is preferred for display and for matching. */
    private val preferredLocale: String = DEFAULT_LOCALE,
    /**
     * Diagnostics sink.
     *
     * A seam rather than a direct `android.util.Log` call: `Log` throws in plain JVM tests
     * ("not mocked"), and this class's logging exists to explain a *silent* failure, which is
     * precisely the behaviour worth testing. Injecting it keeps the tests on the JVM.
     */
    private val log: (String, String) -> Unit = { tag, message -> Log.w(tag, message) },
    /** Distinct queries retained. Small: a detail screen only ever revisits a few titles. */
    private val cacheSize: Int = DEFAULT_CACHE_SIZE,
) {

    // Access-ordered LRU via LinkedHashMap's accessOrder. Not a coroutine-safe structure, but
    // every use is inside a suspend function reached from the same dispatcher, so accesses are
    // serialised by the caller's context.
    private val searchCache = object : LinkedHashMap<String, List<KitsuMetadataMatch>>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<KitsuMetadataMatch>>,
        ) = size > cacheSize
    }

    /**
     * Best Kitsu match for [entry], or null when none is found.
     *
     * Prefers re-fetching by stored id over re-searching: an id is exact, whereas a loose text
     * search could now match a different title — a sequel, a remake — if the original's
     * listing changed.
     */
    suspend fun findBestMatch(entry: LibraryEntryEntity): KitsuMetadataMatch? {
        if (entry.mediaType == MediaType.MUSIC) {
            // Kitsu has no music catalogue. Null is the honest answer; synthesising an anime
            // "match" for an album would be actively harmful.
            return null
        }

        entry.kitsuId?.toLongOrNull()?.let { id ->
            fetchById(id, entry.mediaType)?.let {
                return KitsuMetadataMatch(it, MATCH_BY_ID, listOf("matched stored Kitsu id"))
            }
        }

        val query = searchQueryFor(entry)
        if (query.isBlank()) return null

        // `cachedSearch` returns ranked output, so the head is the best fit and its score is
        // meaningful to the caller rather than being an internal ranking detail.
        return cachedSearch(query, entry.mediaType).firstOrNull()
    }

    /**
     * Identifies [entry] and persists the result.
     *
     * @return the match that was written, or null when nothing was persisted.
     */
    suspend fun identifyAndPersist(entry: LibraryEntryEntity): KitsuMetadataMatch? {
        val match = runCatching { findBestMatch(entry) }
            .onFailure { log(TAG, "Kitsu identification failed for ${entry.id}: ${it.message}") }
            .getOrNull()
            ?: return null

        return runCatching {
            write(apply(entry, match.metadata))
            match
        }.onFailure {
            log(TAG, "Could not persist Kitsu match for ${entry.id}: ${it.message}")
        }.getOrNull()
    }

    /**
     * Ranks Kitsu candidates for [query], best first.
     *
     * Used by a "this isn't right, pick another" affordance: the top result is often correct,
     * and showing ranked alternatives is how a user recovers from a bad automatic match.
     *
     * Returns empty rather than throwing for music, which Kitsu does not catalogue.
     */
    suspend fun rankCandidates(query: String, mediaType: MediaType): List<KitsuMetadataMatch> {
        if (mediaType == MediaType.MUSIC || query.isBlank()) return emptyList()

        return cachedSearch(query, mediaType)
    }

    // ---- Lookup -------------------------------------------------------------

    /**
     * Searches and maps, memoised.
     *
     * A failed search is cached as an empty list too, deliberately: without that, a Kitsu
     * outage would mean every screen-open retries and hammers a service that is already
     * struggling. The entry simply stays unidentified until the next process start.
     */
    private suspend fun cachedSearch(query: String, mediaType: MediaType): List<KitsuMetadataMatch> {
        searchCache[query]?.let { return it }

        val results = runCatching { search(query, mediaType) }
            .onFailure { log(TAG, "Kitsu search failed for \"$query\": ${it.message}") }
            .getOrDefault(emptyList())

        val ranked = KitsuMetadataMatcher.rank(query, results, preferredLocale)
        searchCache[query] = ranked
        return ranked
    }

    /**
     * Anime and manga are searched through different endpoints and mapped by different
     * functions, but share one shape, so the two halves are kept separate and joined only
     * where the result type is the same.
     */
    private suspend fun search(query: String, mediaType: MediaType): List<KitsuMetadata> =
        when (mediaType) {
            MediaType.MANGA -> client.searchManga(query, limit = SEARCH_LIMIT)
                .getOrThrow()
                .resources
                .mapNotNull { KitsuMapper.toMetadata(it.id, it.attributes, preferredLocale) }

            MediaType.MUSIC -> emptyList()

            else -> client.searchAnime(query, limit = SEARCH_LIMIT)
                .getOrThrow()
                .resources
                .mapNotNull { KitsuMapper.toMetadata(it.id, it.attributes, preferredLocale) }
        }

    /** Fetches a known Kitsu id, for refreshing an already-identified entry. */
    private suspend fun fetchById(id: Long, mediaType: MediaType): KitsuMetadata? =
        when (mediaType) {
            MediaType.MANGA -> client.manga(id).getOrNull()
                ?.let { KitsuMapper.toMetadata(id, it, preferredLocale) }
            MediaType.MUSIC -> null
            else -> client.anime(id).getOrNull()
                ?.let { KitsuMapper.toMetadata(id, it, preferredLocale) }
        }

    // ---- Persistence --------------------------------------------------------

    /**
     * Writes Kitsu metadata onto an entry.
     *
     * ### What is deliberately *not* written
     * - **title** — the extension's title is what the user saw when they added the entry.
     *   Overwriting it with Kitsu's would silently rename their library.
     * - **description** — Kitsu's synopsis is better than a scraped one, but changing an
     *   already-populated field is a visible edit and belongs to an explicit refresh action.
     *
     * **`artworkUrl` is the one field that *is* replaced.** Extension artwork is frequently a
     * dead CDN link or a low-resolution thumbnail, and a 3:4 poster from Kitsu is a strict
     * improvement in every case. It is replaced only when Kitsu actually supplies one; see the
     * `?:` below.
     * - **status** — Kitsu's `on_hold` and the extension's vocabulary do not line up reliably,
     *   and a confidently wrong status is worse than the one already stored.
     *
     * Only additive fields are written, so identification can never make an entry *worse* than
     * it was before.
     */
    private fun apply(entry: LibraryEntryEntity, metadata: KitsuMetadata): LibraryEntryEntity =
        entry.copy(
            kitsuId = metadata.kitsuId.toString(),
            kitsuCoverUrl = metadata.coverUrl ?: entry.kitsuCoverUrl,
            kitsuOriginalTitle = metadata.originalTitle ?: entry.kitsuOriginalTitle,
            kitsuRating = metadata.averageRating ?: entry.kitsuRating,
            artworkUrl = metadata.posterUrl ?: entry.artworkUrl,
            // Kitsu genres come from a separate endpoint, so this is empty unless a caller has
            // already loaded them. The extension's scraped genres are kept when it has any.
            genres = if (entry.genres.isEmpty()) emptyList() else entry.genres,
        )

    /**
     * The string to search Kitsu with.
     *
     * `kitsuOriginalTitle` is preferred when recorded: it is Kitsu's own canonical spelling from
     * an earlier successful match, so it is the form most likely to be indexed.
     */
    private fun searchQueryFor(entry: LibraryEntryEntity): String =
        entry.kitsuOriginalTitle?.takeIf { it.isNotBlank() } ?: entry.title

    private companion object {
        const val TAG = "AurisKitsu"
        const val DEFAULT_LOCALE = "en"
        const val DEFAULT_CACHE_SIZE = 32

        /** Kitsu caps `page[limit]` at 20; asking for more wastes a round trip for nothing. */
        const val SEARCH_LIMIT = 10

        /**
         * Score for an id-based hit.
         *
         * Far above anything [KitsuMetadataMatcher] can produce, so a known id always wins over
         * a text match — an id is exact and a text match is not.
         */
        const val MATCH_BY_ID = 1_000
    }
}