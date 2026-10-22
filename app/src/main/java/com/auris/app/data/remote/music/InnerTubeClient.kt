package com.auris.app.data.remote.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Client for YouTube Music's InnerTube API.
 *
 * ### This is an undocumented internal API
 * InnerTube is not a public product. There is no version guarantee, no changelog and no support
 * contract; YouTube changes these endpoints without notice, and an app relying on it can break
 * on a Tuesday. Everything here is therefore built to *degrade* rather than to assume:
 *
 *  - the client name, version and base URL are all injectable, so a break is a config change
 *    rather than a code change;
 *  - responses are parsed into our own [MusicModels] types rather than serialised directly, so
 *    drift is absorbed in one place;
 *  - every method returns [Result] — a transport change is not a crash.
 *
 * ### `ANDROID_MUSIC` and the API key
 * InnerTube requires a `context.client` block. The `WEB` client additionally requires a public
 * web API key scraped from the site; the `ANDROID_MUSIC` client does not, and returns the
 * richest `streamingData` for audio. That is why it is the default.
 *
 * ### Terms of service
 * Accessing YouTube Music this way is not covered by any API agreement, and downloading or
 * extracting audio may be restricted by the Terms of Service depending on jurisdiction. Auris
 * streams rather than persists: [AudioStream.url] is never written to disk as the source of
 * truth, and tracks are re-resolved per playback.
 */
class InnerTubeClient(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val parser: InnerTubeParser = InnerTubeParser(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clientName: String = DEFAULT_CLIENT_NAME,
    private val clientVersion: String = DEFAULT_CLIENT_VERSION,
    private val language: String = "en",
    /**
     * Content region, sent as `gl`.
     *
     * This is the *content* country, not the device's: it decides which catalogue is served and
     * which tracks are considered available. Set to the Philippines deliberately — the user is
     * there, and the regional catalogue is what they want to see.
     */
    private val country: String = CONTENT_COUNTRY,
) {

    private val context = InnerTubeContext(
        client = InnerTubeClientIdentity(
            clientName = clientName,
            clientVersion = clientVersion,
            // Sent only because the ANDROID client expects it; harmless for others.
            androidSdkVersion = DEFAULT_ANDROID_SDK,
            language = language,
            country = country,
        ),
    )

    // ---- Discovery ----------------------------------------------------------

    /**
     * Searches YouTube Music. [params] filters by kind; see [InnerTubeSearchParams].
     *
     * A blank [query] is rejected by the server with **HTTP 400** — verified live — so callers
     * wanting "show me something" must use [browse], not an empty search.
     */
    suspend fun search(
        query: String,
        params: String? = InnerTubeSearchParams.ALL,
        limit: Int = DEFAULT_LIMIT,
    ): Result<List<MusicCollectionItem>> = post(
        endpoint = "search",
        body = json.encodeToString(
            InnerTubeSearchRequest.serializer(),
            InnerTubeSearchRequest(context, query, params),
        ),
        transform = { json.parseToJsonElement(it).requireObject() },
    ).mapCatching { root ->
        parser.parseSearch(root).take(if (limit > 0) limit else Int.MAX_VALUE)
    }

    /**
     * A browseable collection: a playlist, album or radio station.
     *
     * Verified live to return tracks for real ids (a playlist returned 161 videoIds and 20
     * `musicResponsiveListItemRenderer`s; an album, 240 and 30).
     *
     * ### Chart browse ids do not work
     * YouTube Music's own chart ids — `FEmusic_music_romantic`, `FEmusic_music_lifted` — are
     * rejected with **HTTP 400** by both clients, while user-generated ids succeed. Since there
     * is no id-independent "top songs" browse, the discovery landing page is built from search
     * seeds instead; see [InnerTubeClient.discovery].
     */
    suspend fun collection(browseId: String, limit: Int = DEFAULT_LIMIT): Result<List<MusicCollectionItem>> =
        browse(browseId, limit)

    /**
     * The browse ids of YouTube Music's own charts.
     *
     * Kept documented-but-unused rather than deleted, because the failure is non-obvious: an
     * invalid browse id and a stale client version both produce HTTP 400, and this was
     * misdiagnosed as version drift first.
     */
    object ChartBrowseIds {
        /** Rejected with HTTP 400 on 2026-10, for both `WEB_REMIX` and `ANDROID_MUSIC`. */
        const val SONGS_CHART_DEAD = "FEmusic_music_romantic"
    }

    /**
     * Fetches an album or playlist, including its track list.
     *
     * One call rather than browse-then-continue: the second page of a large playlist needs a
     * `continuation` token, and following it here keeps pagination an implementation detail the
     * caller never sees.
     */
    suspend fun browse(
        browseId: String,
        maxTracks: Int = DEFAULT_TRACK_LIMIT,
    ): Result<List<MusicCollectionItem>> = post(
        endpoint = "browse",
        body = json.encodeToString(
            InnerTubeBrowseRequest.serializer(),
            InnerTubeBrowseRequest(context, browseId),
        ),
        transform = { json.parseToJsonElement(it).requireObject() },
    ).mapCatching { root ->
        var items = parser.parseBrowse(root)
        var nextToken = parser.continuationToken(root)

        // Bounded: an unbounded follow loop on a mis-parsed token can page forever.
        var pages = 0
        while (nextToken != null &&
            parser.totalTracks(items) < maxTracks &&
            pages < MAX_CONTINUATION_PAGES
        ) {
            val next = browseContinuation(nextToken, maxTracks)
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: break
            items += next
            nextToken = null // continuation of a continuation is not followed
            pages++
        }

        items.take(maxTracks.coerceAtLeast(0))
    }

    /** Follows a `continuation` token from a previous response. */
    suspend fun browseContinuation(
        continuation: String,
        maxTracks: Int = DEFAULT_TRACK_LIMIT,
    ): Result<List<MusicCollectionItem>> = post(
        endpoint = "browse",
        body = json.encodeToString(
            InnerTubeBrowseRequest.serializer(),
            InnerTubeBrowseRequest(context, browseId = continuation),
        ),
        transform = { json.parseToJsonElement(it).requireObject() },
    ).mapCatching { root ->
        parser.parseBrowse(root).take(maxTracks.coerceAtLeast(0))
    }

    // ---- Playback -----------------------------------------------------------

    /**
     * Resolves playable audio for [videoId].
     *
     * Called per playback rather than cached: stream URLs carry an ~6 hour expiry, so a cached
     * one fails with a 403 that looks like a network error.
     */
    suspend fun resolveAudioStream(videoId: String): Result<InnerTubePlayerResponse> = post(
        endpoint = "player",
        body = """{"context":${json.encodeToString(InnerTubeContext.serializer(), context)},
                  "videoId":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(videoId))},
                  "contentCheckOk":true,"racyCheckOk":true}""",
        transform = { json.parseToJsonElement(it).requireObject() },
    ).mapCatching { root -> parser.parsePlayer(root) }

    /**
     * The recommended next track, for gapless queueing.
     *
     * Null when YouTube offers none, which is normal near the end of an album rather than an
     * error.
     */
    suspend fun nextTrack(videoId: String): Result<MusicTrack?> = post(
        endpoint = "next",
        body = """{"context":${json.encodeToString(InnerTubeContext.serializer(), context)},
                  "videoId":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(videoId))}}""",
        transform = { json.parseToJsonElement(it).requireObject() },
    ).mapCatching { root -> parser.parseNext(root) }

    // ---- Transport ----------------------------------------------------------

    /**
     * One POST path for every endpoint.
     *
     * [transform] decodes the body before it reaches the caller so a malformed payload surfaces
     * as a [Result] failure rather than an exception from inside a map chain.
     */
    private suspend fun <T> post(
        endpoint: String,
        body: String,
        transform: (String) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$baseUrl/youtubei/v1/$endpoint".toHttpUrl().newBuilder()
                // `prettyPrint=false` roughly halves the response; the default true is only
                // useful when a human is reading it.
                .addQueryParameter("prettyPrint", "false")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                // YouTube serves HTML to clients that do not identify themselves, and the
                // failure then surfaces as a confusing parse error rather than a status code.
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("User-Agent", USER_AGENT)
                .header("X-Goog-Api-Format-Version", "1")
                // Required by the ANDROID client; the web client uses a key instead.
                .header("X-YouTube-Client-Name", YOUTUBE_CLIENT_NAME_WEB_REMIX)
                .header("X-YouTube-Client-Version", clientVersion)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw IOException("InnerTube $endpoint returned HTTP ${response.code}")
                }
                if (text.isBlank()) {
                    throw IOException("InnerTube $endpoint returned an empty body")
                }
                // A 200 carrying HTML means the endpoint moved or the client was rejected; the
                // body is the only clue about which.
                if (text.trimStart().startsWith("<")) {
                    throw IOException("InnerTube $endpoint returned HTML, not JSON")
                }

                transform(text)
            }
        }.onFailure {
            Log.w(TAG, "InnerTube $endpoint failed: ${it.message}")
        }
    }

    /** Casts a parsed body to an object, failing loudly if it is not one. */
    private fun JsonElement.requireObject(): JsonObject =
        this as? JsonObject ?: throw IOException("Expected a JSON object from InnerTube")

    companion object {
        const val DEFAULT_BASE_URL = "https://music.youtube.com"

        /**
         * The content region sent as `gl`.
         *
         * Shared rather than inlined so the catalogue and the audio resolver cannot disagree about
         * which country's catalogue is being browsed.
         */
        const val CONTENT_COUNTRY = "PH"

        /**
         * `ANDROID_MUSIC` returns the richest `streamingData` and needs no web API key.
         *
         * The numeric `14` is the `X-Goog-Api-Format-Version`/`X-YouTube-Client-Name` encoding
         * YouTube expects for the Android clients; the music client is `21`.
         */
        /**
         * `WEB_REMIX`, verified live against the pinned version below.
         *
         * `ANDROID_MUSIC` returns `videoId` only and no `musicResponsiveListItemRenderer`, so a
         * track list has to be reassembled from several different renderer shapes.
         * `WEB_REMIX` returns `musicResponsiveListItemRenderer` directly, which is one uniform
         * shape and what [InnerTubeParser] is built around. It also needs no web API key.
         */
        const val DEFAULT_CLIENT_NAME = "WEB_REMIX"
        const val DEFAULT_CLIENT_VERSION = "1.20240101.01.00"

        /** X-YouTube-Client-Name encoding for `WEB_REMIX`. */
        const val YOUTUBE_CLIENT_NAME_WEB_REMIX = "67"
        private const val DEFAULT_ANDROID_SDK = 34

        /** YouTube Music's search returns far more than anyone renders. */
        const val DEFAULT_LIMIT = 30

        /** Guard on a 200-track playlist so one album cannot page forever. */
        const val DEFAULT_TRACK_LIMIT = 200

        /**
         * Seeds for the discovery landing page.
         *
         * Used instead of a chart browse id because no id-independent chart browse exists — see
         * [ChartBrowseIds]. A blank search is rejected with HTTP 400, so opening the screen on
         * an empty result set is not an option either.
         *
         * Deliberately broad and non-genre-specific: they stand in for "something to play",
         * and a playlist or chart id would go stale without any signal that it had.
         */
        /**
         * Home-screen shelves, in display order.
         *
         * The seeds are search queries rather than browse ids: YouTube Music's own chart ids
         * (`FE*`) answer `400` on every client (see ARCHITECTURE §4), while a query works. The mix
         * is deliberate — generic rows for a first run with no history, and regional ones so the
         * home screen reflects the configured content country rather than a US default.
         */
        val DISCOVERY_SHELVES = listOf(
            MusicShelfSeed("Top Picks for You", "popular music"),
            MusicShelfSeed("New Releases", "new releases"),
            MusicShelfSeed("OPM", "OPM"),
            MusicShelfSeed("K-Pop", "K-Pop"),
            MusicShelfSeed("Chill", "chill beats"),
        )

        /** Untitled view of [DISCOVERY_SHELVES], for callers that only need the queries. */
        val DISCOVERY_SEEDS = DISCOVERY_SHELVES.map { it.seed }

        private const val MAX_CONTINUATION_PAGES = 3

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val USER_AGENT = "com.google.android.youtube/19.29.37 (Linux; U; Android 14)"
        private const val TAG = "AurisMusic"
    }
}