package com.auris.app.data.remote.music

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The YouTube Music content we consume, as domain models.
 *
 * ### Why the domain models are separate from the wire shape
 * InnerTube responses are deeply nested and undocumented, and their exact shape varies by
 * endpoint and drifts over time. Keeping [MusicCollectionItem] and friends as our own types means
 * a change in YouTube's JSON is absorbed by the parser rather than propagating into Room, the
 * UI and the tests.
 */

/** A playable track. */
data class MusicTrack(
    /** YouTube video id. Stable, and what InnerTube calls `videoId`. */
    val videoId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    /** Track duration in milliseconds; null when YouTube does not report it. */
    val durationMs: Long?,
    val artworkUrl: String?,
    /** Album art at a larger size, for the player's centred square art. */
    val largeArtworkUrl: String?,
    val playlistId: String?,
    /** Index within its album/playlist, 0-based. Null when not supplied. */
    val trackIndex: Int?,
) {
    /**
     * `videoId` as a Room `sourceId`.
     *
     * Deliberately a `Long`-safe string rather than an int: YouTube ids are opaque strings, not
     * numbers, and coercing them to a numeric column would corrupt any id that is not purely
     * digits.
     */
    val sourceId: String get() = videoId
}

/** An album. */
data class MusicAlbum(
    val browseId: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String?,
    val year: Int?,
    val trackCount: Int?,
)

/** A playlist or other browsable collection. */
data class MusicPlaylist(
    val browseId: String,
    val title: String,
    val subtitle: String?,
    val artworkUrl: String?,
    val trackCount: Int?,
)

/**
 * A playable audio stream for one track.
 *
 * ### Why the signature matters
 * The URL InnerTube returns carries an `expire` timestamp — typically about six hours. Storing
 * a stream URL in `library_units.stream_url` therefore guarantees eventual 403. The field is
 * kept on the unit only as a *cache*, and the player re-resolves when playback fails.
 */
data class AudioStream(
    val url: String,
    val mimeType: String,
    val bitrate: Int?,
    val durationMs: Long?,
    /** Epoch millis after which [url] is no longer valid. */
    val expiresAtMs: Long?,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtMs != null && nowMs >= expiresAtMs

    /**
     * True when the stream is usable.
     *
     * Treated as best-effort: a stream with no known expiry is assumed valid, because refusing
     * it would break playback whenever YouTube omits the field.
     */
    fun isPlayable(nowMs: Long = System.currentTimeMillis()): Boolean =
        url.isNotBlank() && !isExpired(nowMs)
}

/**
 * Why a resolve produced no audio, stated by whichever provider answered.
 *
 * Source-agnostic on purpose. The catalogue comes from YouTube's own API while audio comes from
 * an Invidious instance, so the two can fail for entirely different reasons — a track that
 * YouTube says is unavailable, versus a public instance that is simply down. Collapsing them
 * into "nothing playable" would tell the user to retry the one case that can never succeed.
 */
data class StreamRefusalInfo(
    val status: String,
    val reason: String? = null,
) {
    companion object {
        /**
         * Minted by a resolver, never sent by a provider: a URL was named and then could not be
         * fetched from this device.
         *
         * Not a content problem — the track exists — which is why it gets its own wording rather
         * than being folded into "unavailable", and why fallback resolvers may try another source
         * after seeing it.
         */
        const val UNFETCHABLE = "UNFETCHABLE"
    }
}

/**
 * The outcome of asking a provider for a track's audio.
 *
 * Carries the metadata that arrived alongside the stream, because a resolve is also the only
 * place some of it is available: YouTube Music's search rows come back with an **empty thumbnail
 * array**, and title and author are only ever confirmed here.
 *
 * A refusal is not a failure. [stream] being null while [refusal] is set means the provider
 * answered and declined; a `Result` failure means it never answered at all. The distinction is
 * what [com.auris.app.ui.music.StreamRefusal] turns into honest wording.
 *
 * ### Why there is no artwork here
 * Artwork is derived from the video id instead ([youtubeThumbnailUrl]), because it costs no
 * request and because the provider's own thumbnail URLs are not trustworthy: Invidious returns
 * instance-relative `/vi/...` paths, and on the instance actually in use those answer with an
 * HTML error page rather than a JPEG (measured 2026-10).
 */
data class ResolvedAudio(
    val stream: AudioStream? = null,
    val refusal: StreamRefusalInfo? = null,
    val title: String? = null,
    val author: String? = null,
    val lengthMs: Long? = null,
) {
    val isPlayable: Boolean get() = stream?.isPlayable() == true
}

/**
 * InnerTube's request envelope.
 *
 * ### `client` is the whole trick
 * InnerTube has no public API. Every request must carry a `context.client` block identifying
 * which YouTube app it claims to be, and YouTube serves a different response shape per client.
 * The `ANDROID_MUSIC` client is used because it returns the richest `streamingData` for audio
 * and does not require a web API key — see [InnerTubeClient].
 */
@Serializable
data class InnerTubeContext(
    @SerialName("client") val client: InnerTubeClientIdentity,
    @SerialName("user") val user: InnerTubeUser? = null,
)

@Serializable
data class InnerTubeClientIdentity(
    /** e.g. `ANDROID_MUSIC`. */
    @SerialName("clientName") val clientName: String,
    /** Pinned. YouTube rejects mismatched major versions intermittently. */
    @SerialName("clientVersion") val clientVersion: String,
    /** Android-only; required by the ANDROID client and harmless elsewhere. */
    @SerialName("androidSdkVersion") val androidSdkVersion: Int? = null,
    @SerialName("hl") val language: String = "en",
    @SerialName("gl") val country: String = "US",
)

@Serializable
data class InnerTubeUser(
    @SerialName("lockedSafetyMode") val lockedSafetyMode: Boolean = false,
)

/** Body of a `search` request. */
@Serializable
data class InnerTubeSearchRequest(
    @SerialName("context") val context: InnerTubeContext,
    @SerialName("query") val query: String,
    /** Opaque filter token; see [InnerTubeSearchParams]. */
    @SerialName("params") val params: String? = null,
)

/** Body of a `browse` request, used for albums, playlists and artist pages. */
@Serializable
data class InnerTubeBrowseRequest(
    @SerialName("context") val context: InnerTubeContext,
    @SerialName("browseId") val browseId: String,
    @SerialName("params") val params: String? = null,
)

/**
 * A parsed `browse` or `search` result: a mix of tracks, albums and playlists.
 *
 * YouTube Music's browse response interleaves section headers, tracks, albums and "shuffle"
 * tiles in one ordered list, so this is deliberately loose — [kind] is what the parser
 * determined, and anything it could not classify is dropped rather than guessed at.
 */
data class MusicCollectionItem(
    val kind: Kind,
    val tracks: List<MusicTrack> = emptyList(),
    val album: MusicAlbum? = null,
    val playlist: MusicPlaylist? = null,
) {
    enum class Kind { TRACK, ALBUM, PLAYLIST, UNKNOWN }
}

/**
 * Response of a `player` request, reduced to what playback needs.
 *
 * Parsed rather than deserialised, for the same reason as [MusicCollectionItem].
 *
 * Extracted by the parser rather than deserialised directly, because `streamingData` contains
 * a mix of audio-only and audio+video adaptive formats and only the former are wanted — serving
 * a video track for audio-only playback wastes an order of magnitude of bandwidth.
 */
data class InnerTubePlayerResponse(
    val playabilityStatus: PlayabilityStatus,
    val audioStreams: List<AudioStream>,
    val videoDetails: VideoDetails?,
) {
    data class PlayabilityStatus(
        val status: String,
        val reason: String?,
    ) {
        /**
         * Whether a stream can be expected.
         *
         * `OK` is the only fully playable state. `LIVE_STREAM_OFFLINE`, `UNPLAYABLE` and the
         * error family all mean the caller should report a reason rather than retry silently.
         */
        val isPlayable: Boolean get() = status == "OK"
    }

    data class VideoDetails(
        val videoId: String,
        val title: String,
        val author: String?,
        val lengthSeconds: Long?,
        val thumbnails: List<String>,
    )
}

/**
 * Opaque filter tokens for search.
 *
 * YouTube Music's search takes no query parameters; filtering is done with base64 protobuf
 * blobs whose contents are undocumented, and the historically-published values have since gone
 * stale.
 *
 * ### Verified live: the "songs" token is dead
 * The widely-cited `EgWKAQIYAWoKEAkQBRAKEAMQBA==` token was tested against both clients on
 * 2026-10 and returns **zero tracks** — `ANDROID_MUSIC` yields no `videoId` at all, and
 * `WEB_REMIX` yields no `musicResponsiveListItemRenderer`. The same query *without* a token
 * returns 123 videoIds and 32 responsive renderers respectively.
 *
 * The tokens are kept because they document what has been tried, but [ALL] is the default and
 * sending a stale one silently returns an empty result set — which looks identical to a parser
 * that matched nothing.
 */
object InnerTubeSearchParams {
    /** No filter. The only value verified to return results. */
    val ALL: String? = null

    /**
     * Historically "songs". Verified dead: returns zero tracks.
     *
     * Kept so the finding is not rediscovered the hard way.
     */
    const val SONGS_DEAD = "EgWKAQIYAWoKEAkQBRAKEAMQBA%3D%3D"
}

/**
 * Raw, unparsed parts of an InnerTube response.
 *
 * Exists so the parser can be tested against real captured JSON without the transport, and so
 * an endpoint whose shape has drifted can be inspected in a debugger without unserialising the
 * whole envelope.
 */
@Serializable
data class InnerTubeBrowseResult(
    val raw: JsonElement? = null,
)