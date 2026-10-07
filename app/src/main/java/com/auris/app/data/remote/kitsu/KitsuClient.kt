package com.auris.app.data.remote.kitsu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Client for Kitsu's `/api/edge` JSON:API.
 *
 * ### What it is for
 * Extensions give us a title scraped from a fan site — often romanised, sometimes Japanese,
 * sometimes a short form. Kitsu supplies the authoritative metadata: clean titles in every
 * locale, a synopsis, and artwork sized for the UI. This client turns the former into the
 * latter.
 *
 * ### Query construction
 * Kitsu filters are written `filter[text]=frieren`. That is **not** URL-safe in the sense
 * OkHttp's `addQueryParameter` needs — the brackets are fine, but the real hazard is naive
 * string concatenation, which breaks on any title containing `&`, `#` or a space. Every
 * parameter therefore goes through [HttpUrl.Builder.addQueryParameter], which encodes
 * correctly. `Spirited Away` and `Fate/Zero` are not hypothetical.
 *
 * ### Rate limiting
 * Kitsu allows roughly 3 requests/second for unauthenticated clients. There is no throttling
 * here — callers batch, and [KitsuClient] is not the right place for a rate limiter that would
 * need to know whether a burst is legitimate.
 */
class KitsuClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : KitsuDataSource {

    // ---- Search -------------------------------------------------------------

    /**
     * Searches anime by free text.
     *
     * `filter[text]` matches title fields loosely, so it returns false positives on short or
     * partial strings. [KitsuMetadataMatch] scores and ranks results rather than returning the
     * raw order; callers should not treat position 0 as the match.
     */
    override suspend fun searchAnime(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<KitsuPage<KitsuAnimeAttributes>> = get(
        path = "anime",
        serializer = KitsuAnimeAttributes.serializer(),
        filters = mapOf("filter[text]" to query),
        limit = limit,
        offset = offset,
    )

    /** Searches manga by free text. Same matching semantics as [searchAnime]. */
    override suspend fun searchManga(
        query: String,
        limit: Int,
        offset: Int,
    ): Result<KitsuPage<KitsuMangaAttributes>> = get(
        path = "manga",
        serializer = KitsuMangaAttributes.serializer(),
        filters = mapOf("filter[text]" to query),
        limit = limit,
        offset = offset,
    )

    /**
     * Anime narrowed to a set of genres.
     *
     * `filter[genres]` takes comma-joined genre **ids**, obtained from [genres] — not names.
     * Passing names returns 0 results with no error, which reads as "no such anime".
     *
     * Verified: `filter[genres]=1` (Action) matched 22,494 titles.
     */
    suspend fun animeByGenres(
        genreIds: List<Long>,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        sort: KitsuSort = KitsuSort.POPULARITY,
    ): Result<KitsuPage<KitsuAnimeAttributes>> = get(
        path = "anime",
        serializer = KitsuAnimeAttributes.serializer(),
        filters = mapOf("filter[genres]" to genreIds.joinToString(",")),
        sort = sort,
        limit = limit,
        offset = offset,
    )

    /** The same narrowing, for manga. */
    suspend fun mangaByGenres(
        genreIds: List<Long>,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
        sort: KitsuSort = KitsuSort.POPULARITY,
    ): Result<KitsuPage<KitsuMangaAttributes>> = get(
        path = "manga",
        serializer = KitsuMangaAttributes.serializer(),
        filters = mapOf("filter[genres]" to genreIds.joinToString(",")),
        sort = sort,
        limit = limit,
        offset = offset,
    )

    // ---- Lookup by id -------------------------------------------------------

    /** Fetches one anime by Kitsu id. Used to refresh stored metadata. */
    override suspend fun anime(id: Long): Result<KitsuAnimeAttributes> =
        getOne("anime", id, KitsuAnimeAttributes.serializer())

    /** Fetches one manga by Kitsu id. */
    override suspend fun manga(id: Long): Result<KitsuMangaAttributes> =
        getOne("manga", id, KitsuMangaAttributes.serializer())

    /**
     * Every genre, id included.
     *
     * There are 62 as of this writing and the set is stable, so this is fetched once and cached
     * by the caller. The ids are required to use [animeByGenres]; a title lookup does not
     * return them.
     */
    suspend fun genres(): Result<List<KitsuGenre>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = builder("genres")
                .addQueryParameter("page[limit]", GENRE_PAGE_SIZE.toString())
                .build()

            val resources = execute(url).resources
            resources.map { resource ->
                KitsuGenre(
                    id = resource.id,
                    type = resource.type,
                    attributes = json.decodeFromJsonElement(KitsuGenreAttributes.serializer(), resource.attributes),
                )
            }
        }.recoverCatching { error ->
            throw IOException("Could not list Kitsu genres: ${error.message}", error)
        }
    }

    // ---- Plumbing -----------------------------------------------------------

    /**
     * One request path for every endpoint.
     *
     * The attributes [serializer] is passed explicitly rather than derived from a `reified T`.
     * `reified` cannot reach a type parameter used inside the nested `runCatching`/`mapNotNull`
     * lambdas below, and naming the serializer makes the decoded shape visible at each call
     * site instead of hiding it behind a generic.
     */
    private suspend fun <T : Any> get(
        path: String,
        serializer: KSerializer<T>,
        filters: Map<String, String> = emptyMap(),
        sort: KitsuSort? = null,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): Result<KitsuPage<T>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = builder(path)
                .apply {
                    // Insertion order is preserved, so filters keep the order written above —
                    // useful when debugging a request against Kitsu's own docs.
                    filters.forEach { (key, value) -> addQueryParameter(key, value) }
                    sort?.let { addQueryParameter("sort", it.field) }
                    addQueryParameter("page[limit]", limit.coerceIn(1, MAX_LIMIT).toString())
                    addQueryParameter("page[offset]", offset.coerceAtLeast(0).toString())
                }
                .build()

            val page = execute(url)

            KitsuPage(
                resources = page.resources.mapNotNull { resource ->
                    // One unparsable record must not fail the page: a single malformed title
                    // on Kitsu's side would otherwise blank out an otherwise good result list.
                    // A record with an unparsable id is dropped too — it could not be stored or
                    // re-fetched, so keeping it would only invite a bad `kitsuId` later.
                    val id = resource.idAsLong ?: return@mapNotNull null
                    runCatching {
                        json.decodeFromJsonElement(serializer, resource.attributes)
                    }.getOrNull()?.let { attributes ->
                        KitsuResource(id, attributes)
                    }
                },
                totalCount = page.totalCount,
                limit = limit,
                offset = offset,
            )
        }.recoverCatching { error ->
            throw IOException("Kitsu request failed: ${error.message}", error)
        }
    }

    /** Single-resource variant, where `data` is an object rather than an array. */
    private suspend fun <T : Any> getOne(
        path: String,
        id: Long,
        serializer: KSerializer<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
            runCatching {
                val url = builder(path).addPathSegment(id.toString()).build()
                val document = execute(url)
                val resource = document.resources.firstOrNull()
                    ?: throw IOException("Kitsu returned no $path/$id")

                json.decodeFromJsonElement(serializer, resource.attributes)
            }.recoverCatching { error ->
                throw IOException("Kitsu lookup failed: ${error.message}", error)
            }
        }

    private fun builder(path: String): HttpUrl.Builder =
        "$baseUrl/$path".toHttpUrl().newBuilder()

    /**
     * Executes and unwraps a document.
     *
     * Resolves the polymorphic `data` member here so no caller sees it: [KitsuPage.resources]
     * is always a list, empty for a single resource rather than erroring.
     */
    private fun execute(url: HttpUrl): ParsedDocument {
        val request = Request.Builder()
            .url(url)
            // Kitsu serves JSON:API only to clients that ask. Without this header the
            // response comes back as HTML and the parse fails with a confusing type error.
            .header("Accept", JSON_API_MEDIA_TYPE)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException(httpFailure(response.code, body, url))
            }
            if (body.isBlank()) {
                throw IOException("Empty response from $url")
            }

            val document = runCatching {
                json.decodeFromString(KitsuDocument.serializer(), body)
            }.getOrElse {
                throw IOException(
                    "Could not parse Kitsu response from $url: ${it.message}",
                )
            }

            document.errors?.firstOrNull()?.let { error ->
                throw IOException(error.detail ?: error.title ?: "Kitsu returned an error")
            }

            return ParsedDocument(
                resources = unwrap(document.data),
                totalCount = document.meta?.count ?: 0,
            )
        }
    }

    /**
     * Builds Kitsu's own message from an error body, falling back to the status.
     *
     * `errors[].detail` is written for humans ("Invalid filter") and a bare `404` is not, so
     * the body is preferred whenever it parses.
     */
    private fun httpFailure(code: Int, body: String, url: HttpUrl): String {
        val detail = runCatching {
            json.decodeFromString(KitsuDocument.serializer(), body)
                .errors?.firstOrNull()?.detail
        }.getOrNull()

        return detail ?: "Kitsu returned HTTP $code for $url"
    }

    /**
     * `data` is an object for a single resource and an array for a collection.
     *
     * Anything else — an empty body, `null` — yields an empty list rather than throwing, so a
     * malformed single-resource response does not read as a crash.
     */
    private fun unwrap(data: JsonElement?): List<KitsuResourceObject> = when (data) {
        is JsonArray -> data.mapNotNull { it.asResource() }
        is JsonObject -> listOfNotNull(data.asResource())
        // Null covers both an absent `data` (error responses) and an explicit JSON null.
        else -> emptyList()
    }

    private fun JsonElement.asResource(): KitsuResourceObject? =
        runCatching { json.decodeFromJsonElement(KitsuResourceObject.serializer(), this) }.getOrNull()

    /** A decoded collection, normalised to a list regardless of endpoint shape. */
    private data class ParsedDocument(
        val resources: List<KitsuResourceObject>,
        val totalCount: Int,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://kitsu.io/api/edge"

        /** Kitsu's JSON:API media type. Required; a plain Accept yields HTML. */
        const val JSON_API_MEDIA_TYPE = "application/vnd.api+json"

        /**
         * Kitsu caps `page[limit]` at 20 for unauthenticated clients. Asking for more does not
         * error — it silently returns 20, which would look like the search found nothing more.
         */
        const val MAX_LIMIT = 20

        const val DEFAULT_LIMIT = 10

        private const val GENRE_PAGE_SIZE = 100
        private const val USER_AGENT = "Auris/1.0 (Android)"

        /** Kitsu's own documentation links, surfaced for error messages. */
        const val DOCS = "https://kitsu.io/docs/api/edge/"
    }
}

/**
 * A resource's id paired with its decoded attributes.
 *
 * ### Why the id is carried alongside rather than inside the attributes
 * `id` is a sibling of `attributes` in JSON:API, not a member of it. Decoding straight to an
 * attributes type therefore throws the id away — and the id is the single most important value
 * Kitsu gives us, since it is what gets stored as `library_entries.kitsu_id` and what makes
 * re-identification exact later.
 *
 * An earlier version of this client returned bare attributes and synthesised ids from the array
 * index, which would have persisted `kitsuId = "0"`. Pairing them here makes that mistake
 * impossible to express.
 */
data class KitsuResource<T>(
    val id: Long,
    val attributes: T,
)

/** One page of results plus the server's own total, for paging UI. */
data class KitsuPage<T>(
    val resources: List<KitsuResource<T>>,
    /**
     * Total matching records across all pages.
     *
     * Verified to be the full count, not the page length — a genre filter reported 22,494 on a
     * page of one. Used for "showing N of M", never for indexing.
     */
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
) {
    /** Attributes only, in order. Convenient when the caller does not care about ids. */
    val items: List<T> get() = resources.map { it.attributes }

    /** Whether another page exists after this one. */
    val hasMore: Boolean get() = offset + resources.size < totalCount
}

/**
 * Kitsu's `sort` field names.
 *
 * The API takes the **attribute name**, not a `-` prefixed variant: `sort=popularityRank`,
 * not `sort=-popularityRank`. Descending order is expressed by the field itself — there is no
 * general direction prefix, so each entry pairs the field with the direction that field already
 * encodes.
 */
enum class KitsuSort(val field: String) {
    /** Descending by popularity rank; Kitsu treats nulls as least popular. */
    POPULARITY("popularityRank"),

    /** Descending by community average rating. */
    RATING("averageRating"),

    /** Descending by favourite count — a better "popular" signal than rank on a long tail. */
    FAVOURITES("favoritesCount"),

    /** Most recently updated first. Useful for "new this season". */
    RECENTLY_UPDATED("updatedAt"),

    /** Title, A-Z. The only lexicographic sort available. */
    TITLE("canonicalTitle"),
}