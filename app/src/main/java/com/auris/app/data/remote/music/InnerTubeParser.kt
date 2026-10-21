package com.auris.app.data.remote.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Turns InnerTube's nested JSON into [MusicModels] types.
 *
 * ### Why a hand-written walker rather than `@Serializable`
 * InnerTube responses are 10-30 levels deep with optional fields that appear and vanish per
 * record, and the shape differs by endpoint *and* by client. Declarative models would need a
 * class per shape and would throw on the first unanticipated variation. A walker that looks for
 * known keys and ignores the rest degrades instead.
 *
 * ### Every lookup is defensive
 * [firstText] returns null rather than throwing, and `musicItemRenderer`/`musicResponsiveListItemRenderer`
 * are checked for presence rather than assumed. A dropped field costs a missing subtitle; an
 * exception would cost the whole page.
 *
 * ### Not verified against the live API
 * YouTube Music is undocumented and drifts. The paths below match what `ANDROID_MUSIC` returns
 * as of the pinned client version, but they are **inference from public reverse-engineering, not
 * a verified contract** — the single largest risk in this layer, and the reason
 * [InnerTubeClient] takes its client version as a parameter so a break is a config change.
 */
class InnerTubeParser {

    // ---- Search -------------------------------------------------------------

    /** Parses a `search` response into tracks, albums and playlists. */
    fun parseSearch(root: JsonObject): List<MusicCollectionItem> = root.collectRenderers()

    /** Parses a `browse` response into tracks, albums and playlists. */
    fun parseBrowse(root: JsonObject): List<MusicCollectionItem> = root.collectRenderers()

    /**
     * Walks the tree collecting every recognised renderer.
     *
     * Recursive rather than path-specific because the nesting depth differs between search,
     * album and playlist responses, and a depth limit is not stable enough to rely on. Bounded
     * to keep a malformed response from recursing deeply.
     */
    private fun JsonObject.collectRenderers(depth: Int = 0): List<MusicCollectionItem> {
        if (depth > MAX_DEPTH) return emptyList()

        val found = mutableListOf<MusicCollectionItem>()

        // A renderer with no video id cannot be played, so it is dropped rather than surfaced as
        // an empty row — the caller would have nothing to put in `sourceId`.
        this["musicItemRenderer"]?.asObject()?.let { found += parseTrack(it) }
        this["musicResponsiveListItemRenderer"]?.asObject()?.let { found += parseTrack(it) }
        this["musicTwoRowItemRenderer"]?.asObject()?.let { renderer ->
            renderer.firstTitle()?.let { title ->
                found += MusicCollectionItem(
                    kind = MusicCollectionItem.Kind.ALBUM,
                    album = MusicAlbum(
                        browseId = renderer.browseId() ?: "",
                        title = title,
                        artist = renderer.secondTitle(),
                        artworkUrl = renderer.thumbnailUrl(THUMB_LARGE),
                        year = null,
                        trackCount = null,
                    ),
                )
            }
        }
        this["musicShelfRenderer"]?.asObject()?.let { shelf ->
            val shelfTitle = shelf.firstTitle()
            if (shelfTitle != null) {
                found += MusicCollectionItem(
                    kind = MusicCollectionItem.Kind.PLAYLIST,
                    playlist = MusicPlaylist(
                        browseId = shelf.browseId() ?: "",
                        title = shelfTitle,
                        subtitle = shelf.firstSubtitle(),
                        artworkUrl = shelf.thumbnailUrl(THUMB_LARGE),
                        trackCount = null,
                    ),
                )
            }
        }

        // Recurse into every object/array child.
        values.forEach { value ->
            when (value) {
                is JsonObject -> found += value.collectRenderers(depth + 1)
                is JsonArray -> value.forEach { element ->
                    (element as? JsonObject)?.let { found += it.collectRenderers(depth + 1) }
                }
                else -> Unit
            }
        }

        return found.filter { it.kind != MusicCollectionItem.Kind.UNKNOWN }
    }

    // ---- Track --------------------------------------------------------------

    /**
     * Parses one track renderer.
     *
     * ### Two shapes, because live responses have two shapes
     * Verified against a real `WEB_REMIX` search on 2026-10:
     *
     * ```
     * flexColumns[0].runs = [{ "text": "Lose Yourself to Dance (feat. Pharrell Williams)" }]
     * flexColumns[1].runs = [{ "text": "Song" }, { "text": " • " }, { "text": "5:54" }]
     * flexColumns[2].runs = [{ "text": "388M plays" }]
     * fixedColumns       = absent
     * thumbnail          = absent for songs
     * ```
     *
     * Note there is **no `accessibilityData`**, so a label-based lookup cannot work here — the
     * columns are positional only. Some responses *do* carry the labels, so both are attempted:
     * labels first when present, position as the fallback.
     *
     * The subtitle is a list of runs, not one string, so `"Song"` and `"5:54"` are separate runs
     * that have to be joined before the parts can be classified.
     */
    private fun parseTrack(renderer: JsonObject): MusicCollectionItem {
        val videoId = renderer.firstVideoId()
            ?: return MusicCollectionItem(MusicCollectionItem.Kind.UNKNOWN)

        // Labelled first: when accessibilityData is present it is the reliable path.
        val labelledTitle = renderer.flexText("title") ?: renderer.flexText("song")
        val positionalTitle = renderer.flexColumnText(0)?.firstOrNull()

        val subtitle = renderer.flexText("subtitle")
            ?: renderer.flexColumnText(1)?.joinToString("") { it }
                ?.takeIf { it.isNotBlank() }
            ?: renderer.fixedText(1)

        // Classify the subtitle's segments: a duration and a media-type token ("Song") are not
        // metadata, and leaving them in produces an artist literally called "Song".
        val segments = subtitle
            ?.split("•")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        // Clock-only: "Daft Punk • 2019 • Discovery" would otherwise read 2019 as the duration.
        val duration = segments.firstNotNullOfOrNull { it.parseClockDurationMs() }
            ?: renderer.durationMs()

        val metadata = segments.filterIndexed { index, segment -> !segment.isNonMetadata(index) }

        val labelledArtist = renderer.flexText("artist")?.let { it.substringBefore("•").trim() }
        val labelledAlbum = renderer.flexText("album")?.let { it.substringBefore("•").trim() }

        return MusicCollectionItem(
            kind = MusicCollectionItem.Kind.TRACK,
            tracks = listOf(
                MusicTrack(
                    videoId = videoId,
                    title = (labelledTitle ?: positionalTitle) ?: videoId,
                    artist = labelledArtist ?: metadata.getOrNull(0),
                    album = labelledAlbum ?: metadata.getOrNull(1),
                    durationMs = duration,
                    artworkUrl = renderer.thumbnailUrl(THUMB_MEDIUM)
                        ?: youtubeThumbnailUrl(videoId),
                    largeArtworkUrl = renderer.thumbnailUrl(THUMB_LARGE)
                        ?: youtubeThumbnailUrl(videoId, large = true),
                    playlistId = renderer.firstPlaylistId(),
                    trackIndex = null,
                ),
            ),
        )
    }

    /**
     * The run texts of `flexColumns[index]`, or null when absent.
     *
     * Positional access is a fallback, not the primary path: the column count and meaning
     * depend on the response, which is why [flexText]'s label match is tried first.
     */
    private fun JsonObject.flexColumnText(index: Int): List<String>? {
        val columns = this["flexColumns"] as? JsonArray ?: return null
        val column = columns.getOrNull(index) as? JsonObject ?: return null
        val renderer = column["musicResponsiveListItemFlexColumnRenderer"] as? JsonObject
            ?: return null
        val runs = (renderer["text"] as? JsonObject)?.ownRuns() ?: return null
        val texts = runs.mapNotNull { it.string("text") }
        return texts.takeIf { it.isNotEmpty() }
    }

    /**
     * The track's duration.
     *
     * Scans every fixed column for the first value that parses as a duration, rather than
     * indexing a fixed position. YouTube Music's `fixedColumns` count varies with the view
     * (index, then duration; or duration alone; or neither on a compact layout), so a positional
     * lookup silently yields null and the track loses its length.
     */
    private fun JsonObject.durationMs(): Long? {
        // Songs carry the duration inside a flex column run; video results use a fixed column.
        // Both are scanned, because neither position is stable across response types.
        (0..3).forEach { index ->
            flexColumnText(index)?.forEach { text ->
                text.parseClockDurationMs()?.let { return it }
            }
        }

        val fromFixed = (this["fixedColumns"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { column ->
                val renderer =
                    column["musicResponsiveListItemFixedColumnRenderer"] as? JsonObject
                renderer?.firstText("text")?.parseDurationMs()
            }
            ?.firstOrNull { it != null }
        if (fromFixed != null) return fromFixed

        return firstText("lengthText")?.parseDurationMs()
            ?: firstText("duration")?.parseDurationMs()
    }

    // ---- Player -------------------------------------------------------------

    /**
     * Parses a `player` response, keeping **only audio** adaptive formats.
     *
     * YouTube returns audio+video pairs alongside audio-only ones. Taking the first entry would
     * give whichever YouTube listed first — often the video one — and pull an order of magnitude
     * more data for an audio-only app.
     */
    fun parsePlayer(root: JsonObject): InnerTubePlayerResponse {
        val playability = root["playabilityStatus"]?.asObject()
        val status = InnerTubePlayerResponse.PlayabilityStatus(
            status = playability?.string("status") ?: "UNPLAYABLE",
            reason = playability?.string("reason"),
        )

        val streamingData = root["streamingData"]?.asObject()
        val expiresAt = streamingData?.string("expiresInSeconds")?.toLongOrNull()
            ?.let { System.currentTimeMillis() + it * 1000L }

        val audioStreams = streamingData
            ?.let { formats ->
                (formats["adaptiveFormats"] as? JsonArray).orEmpty()
                    .mapNotNull { it.asObject() }
            }
            .orEmpty()
            .filter { format ->
                val mime = format.string("mimeType").orEmpty()
                // Audio-only: mimeType starts "audio/". Catching it this way rather than by
                // checking for a video codec list, because YouTube adds codecs to the audio-only
                // formats too.
                mime.startsWith("audio/", ignoreCase = true)
            }
            .mapNotNull { format ->
                val url = format.string("url")
                    ?: format["signatureCipher"]?.asObject()?.string("url")
                if (url.isNullOrBlank()) return@mapNotNull null

                AudioStream(
                    url = url,
                    mimeType = format.string("mimeType").orEmpty(),
                    bitrate = format.int("averageBitrate"),
                    durationMs = format.long("approxDurationMs")
                        ?: root.videoDetailsDurationSeconds()?.times(1000),
                    expiresAtMs = expiresAt,
                )
            }
            // Highest bitrate first, so a player that picks index 0 gets the best available
            // rather than whatever order the response used.
            .sortedByDescending { it.bitrate ?: 0 }

        val details = root["videoDetails"]?.asObject()

        return InnerTubePlayerResponse(
            playabilityStatus = status,
            audioStreams = audioStreams,
            videoDetails = details?.let {
                InnerTubePlayerResponse.VideoDetails(
                    videoId = it.string("videoId").orEmpty(),
                    title = it.string("title").orEmpty(),
                    author = it.string("author"),
                    lengthSeconds = it.long("lengthSeconds"),
                    thumbnails = it.thumbnails(),
                )
            },
        )
    }

    /** Parses a `next` response into its recommended track, or null. */
    fun parseNext(root: JsonObject): MusicTrack? =
        root.collectRenderers().firstNotNullOfOrNull { item -> item.tracks.firstOrNull() }

    // ---- Continuation -------------------------------------------------------

    /**
     * Finds the `continuation` token for the next page, or null.
     *
     * Returned as null rather than an empty string, so a caller cannot loop on a blank token.
     */
    fun continuationToken(root: JsonObject): String? {
        // `continuations` is an *array* of wrapper objects, so the element has to be unwrapped
        // twice before `continuation` is reachable.
        (root["continuations"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.forEach { wrapper ->
                val nextData = wrapper["nextContinuationData"] as? JsonObject
                nextData?.string("continuation")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        return findContinuationToken(root)
    }

    /** Depth-limited search for a `continuation` string anywhere in the response. */
    private fun findContinuationToken(element: JsonElement, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null

        when (element) {
            is JsonObject -> {
                // Only a `nextContinuationData` token is a paging cursor; other continuation
                // strings (shuffle, radio seeds) are not and would page into unrelated content.
                element.string("continuation")
                    ?.takeIf { element["nextContinuationData"] != null && it.isNotBlank() }
                    ?.let { return it }
                element.values.forEach { value ->
                    findContinuationToken(value, depth + 1)?.let { return it }
                }
            }
            is JsonArray -> element.forEach { item ->
                findContinuationToken(item, depth + 1)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    // ---- Accessors ----------------------------------------------------------

    private fun JsonObject.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.firstText(key: String): String? =
        runs(key).firstNotNullOfOrNull { it.string("text") }

    /** Runs of this object's own `runs` array. */
    private fun JsonObject.ownRuns(): List<JsonObject> =
        (this["runs"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    /** Runs of a named child's `runs` array, e.g. `firstText("title")` reads `title.runs`. */
    private fun JsonObject.runs(key: String): List<JsonObject> =
        (this[key] as? JsonObject)?.ownRuns().orEmpty()

    /**
     * Text of the run whose accessibility label matches [label].
     *
     * `runs` carries an `accessibilityData.label` naming each column ("Artist", "Album",
     * "3:45"). Indexing by position instead would break the first time YouTube inserts a column,
     * which it does without warning.
     */
    private fun JsonObject.flexText(label: String): String? =
        (this["flexColumns"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject }
            ?.firstNotNullOfOrNull { column ->
                (column["text"] as? JsonObject)?.ownRuns()
                    ?.firstOrNull { run -> run.accessibilityLabel() == label.lowercase() }
                    ?.string("text")
            }

    /**
     * The accessibility label on a `runs` entry, lowercased for comparison.
     *
     * YouTube nests it as `accessibilityData.accessibilityData.label`, which is easy to read as
     * one level rather than two.
     */
    private fun JsonObject.accessibilityLabel(): String? {
        val accessibility = this["accessibilityData"] as? JsonObject ?: return null
        return (accessibility["accessibilityData"] as? JsonObject)
            ?.let { it.string("label") }
            ?.lowercase()
    }

    /**
     * Text of a fixed column by position.
     *
     * Positions are stable for the trailing columns (index 1 is the subtitle, 2 the duration),
     * unlike `flexColumns`, which is why title and artist are matched by label rather than by
     * index.
     */
    private fun JsonObject.fixedText(index: Int): String? {
        val column = (this["fixedColumns"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.getOrNull(index)
            ?: return null
        val renderer = column["musicResponsiveListItemFixedColumnRenderer"] as? JsonObject
            ?: return null
        return renderer.firstText("text")
    }

    private fun JsonObject.firstTitle(): String? =
        firstText("title") ?: runs("title").firstNotNullOfOrNull { it.string("text") }

    private fun JsonObject.secondTitle(): String? =
        (this["secondTitle"] as? JsonObject)?.firstText("text")

    private fun JsonObject.firstSubtitle(): String? =
        runs("subtitle").joinToString(" ") { it.string("text").orEmpty() }.takeIf { it.isNotBlank() }

    private fun JsonObject.firstVideoId(): String? =
        string("videoId")
            ?: ((this["navigationEndpoint"] as? JsonObject)
                ?.get("watchEndpoint") as? JsonObject)?.string("videoId")

    private fun JsonObject.firstPlaylistId(): String? =
        ((this["navigationEndpoint"] as? JsonObject)
            ?.get("watchEndpoint") as? JsonObject)
            ?.get("playlistId")?.jsonPrimitive?.contentOrNull

    private fun JsonObject.browseId(): String? =
        string("browseId")
            ?: ((this["navigationEndpoint"] as? JsonObject)
                ?.get("browseEndpoint") as? JsonObject)?.string("browseId")

    /**
     * A thumbnail URL at [preferredWidth].
     *
     * Falls back *down* to the largest available rather than up: a 60px image scaled to a
     * player's 512dp artwork is worse than a slightly small large.
     */
    private fun JsonObject.thumbnailUrl(preferredWidth: Int): String? {
        val thumbnails = (this["thumbnail"] as? JsonObject)
            ?.get("thumbnails") as? JsonArray ?: return null

        val candidates = thumbnails.mapNotNull { it as? JsonObject }
            .mapNotNull { thumb ->
                thumb.string("url")?.let { thumb.int("width")?.let { w -> w to it } }
            }
            .sortedBy { it.first }

        return candidates.firstOrNull { it.first >= preferredWidth }?.second
            ?: candidates.lastOrNull()?.second
    }

    private fun JsonObject.thumbnails(): List<String> {
        val thumbnail = this["thumbnail"] as? JsonObject ?: return emptyList()
        return (thumbnail["thumbnails"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("url") }
            .orEmpty()
    }

    private fun JsonObject.videoDetailsDurationSeconds(): Long? = long("lengthSeconds")

    /** Sums track counts so [InnerTubeClient] can decide whether to follow a continuation. */
    fun totalTracks(items: List<MusicCollectionItem>): Int =
        items.sumOf { it.tracks.size } + items.sumOf { it.album?.trackCount ?: 0 }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    companion object {
        private const val MAX_DEPTH = 24

        /** Row-type tokens YouTube puts in the subtitle column; not metadata. */
        private val MEDIA_TYPE_TOKENS = listOf(
            "Song", "Video", "Music video", "Podcast", "Episode",
            "Album", "Single", "EP", "Playlist",
        )

        /**
         * A popularity figure: `921K views`, `388M plays`, `1,204,555 listeners`.
         *
         * ### Why a pattern and not a blocklist of words
         * Blocking the word "play" misses `views` and `listeners`, and blocking each unit
         * individually means the next one YouTube adds shows up as an album title in the UI.
         * Matching the shape — digits, an optional magnitude suffix, then a metric word — is
         * stable against new units and new magnitudes.
         *
         * The leading digit requirement is what keeps a real album titled "1979" from being
         * discarded as a count.
         */
        private val POPULARITY_COUNT = Regex(
            """^[\d][\d.,]*\s*[KMB]?\s*(plays?|views?|listeners?|subscribers?|streams?)$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * A bare release year: `2019`.
         *
         * YouTube Music routinely puts one in the subtitle — `"Artist • Album • 2019"` — and it
         * is neither the artist nor the album. Letting it through would push the real album out
         * of the second slot, so `"Daft Punk • 2019 • Discovery"` reported its album as *2019*.
         *
         * The year is still parsed separately by [extractYear]; it is only excluded from the
         * artist/album slots. Bounded to 1900–2099 so a track count like "12" or a track
         * duration is never mistaken for one.
         */
        private val BARE_YEAR = Regex("""^(19|20)\d{2}$""")

        /**
         * Whether [segment] describes the row rather than the work, and so must not become a
         * title, artist or album. [index] is the segment's position in the subtitle.
         *
         * ### Why position matters for [MEDIA_TYPE_TOKENS]
         * The tokens are genuinely ambiguous: "Album", "EP" and "Single" are type names, and they
         * are also ordinary words that real releases are titled with. Filtering them everywhere
         * makes the track whose album *is* "Album" report no album at all — which is not a rare
         * case being protected against, it is a regression the tests caught the moment the
         * tokens were added.
         *
         * YouTube only ever puts the row's media type **first** — `"Song • Artist • Album"` —
         * so restricting the check to index 0 removes the ambiguity instead of trading one
         * mistake for another. Every other filter below is position-independent, because a
         * duration or a view count is a count wherever it appears.
         */
        private fun String.isNonMetadata(index: Int): Boolean =
            parseClockDurationMs() != null ||
                (index == 0 && MEDIA_TYPE_TOKENS.any { it.equals(this, ignoreCase = true) }) ||
                POPULARITY_COUNT.matches(this) ||
                BARE_YEAR.matches(this)

        private const val THUMB_MEDIUM = 160
        private const val THUMB_LARGE = 544
    }
}

/**
 * `3:45` / `1:02:03` to milliseconds — a duration **only if it is written as a clock**.
 *
 * ### Why this exists separately from [parseDurationMs]
 * A bare number is ambiguous. In a free-text subtitle, YouTube Music very often puts a release
 * *year* — `"Daft Punk • 2019 • Discovery"` — and [parseDurationMs] would read `2019` as
 * 2,019 seconds and report a 33-minute track. It would also drop the year from the metadata,
 * since the same call classifies it as non-metadata.
 *
 * Requiring a colon disambiguates: a number written without one, in prose, is a year or a
 * count. The bare-number form remains valid for [parseDurationMs]'s other callers, which read
 * the server's explicit `lengthText`/`duration` fields where a raw second count is expected.
 */
internal fun String.parseClockDurationMs(): Long? =
    if (contains(':')) parseDurationMs() else null

/** `3:45` / `1:02:03` / `45` to milliseconds. */
internal fun String.parseDurationMs(): Long? {
    val parts = split(':').mapNotNull { it.trim().toLongOrNull() }
    if (parts.isEmpty()) return null
    return when (parts.size) {
        1 -> parts[0] * 1000L
        2 -> (parts[0] * 60 + parts[1]) * 1000L
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
        else -> null
    }
}

/** The first 4-digit run in a subtitle like "Album • 2019 • 12 songs". */
internal fun String.extractYear(): Int? =
    Regex("""\b(19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()

/**
 * A thumbnail for a YouTube video id, derived rather than fetched.
 *
 * ### Why this exists
 * Song rows from `musicResponsiveListItemRenderer` carry **no `thumbnail` object at all** —
 * verified against a live `WEB_REMIX` search, and the reason blank squares were ever visible. The
 * artwork is not in the search response, but it *is* derivable, because YouTube serves a
 * predictable static image per video id. Deriving it costs **zero API calls**.
 *
 * That matters more than it used to. The previous approach resolved each visible row through the
 * audio provider purely to read its artwork — and the audio provider is now a *rate-limited
 * Invidious instance*. Measured 2026-10: ten successive `/api/v1/videos` requests returned five
 * successes and then a run of `500 This content isn't available, try again later.` Warming twelve
 * rows on every catalogue load spent that budget before the user tapped anything, so the tap
 * itself was the request that got throttled. Artwork must not compete with playback.
 *
 * ### Why not the provider's own thumbnail
 * A resolve does return thumbnail URLs, but they are instance-relative — Invidious hands back
 * `https://<instance>/vi/<id>/maxres.jpg`, and on `invidious.f5.si` that path answers with an
 * **HTML error page, not a JPEG** (7,070 bytes of `text/html`, measured 2026-10). Rendering it
 * would paint broken images on every played track.
 *
 * [large] selects `hqdefault` (480×360) over the smaller `mqdefault` (320×180).
 * `maxresdefault` is deliberately avoided: it 404s for videos that never had a 720p source, and a
 * missing image is worse than a smaller one.
 */
internal fun youtubeThumbnailUrl(videoId: String, large: Boolean = false): String =
    "https://i.ytimg.com/vi/$videoId/${if (large) "hqdefault" else "mqdefault"}.jpg"
