package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What an Invidious `/api/v1/videos/{id}` body reduces to.
 *
 * Carries the chosen stream *and* the metadata that came with it, because a resolve is the only
 * place some of it exists: YouTube Music's search rows return an **empty thumbnail array**, so
 * title, author and artwork are first learned here.
 */
internal data class InvidiousVideoSummary(
    val title: String? = null,
    val author: String? = null,
    val lengthMs: Long? = null,
    /** Count of **audio-only** formats. The resolver will also accept a muxed one; this is the pure count. */
    val audioFormats: Int = 0,
    val totalFormats: Int = 0,
    val bestAudioUrl: String? = null,
    val bestAudioMimeType: String? = null,
    val bestAudioBitrate: Int? = null,
    val error: String? = null,
)

/**
 * Reduces an `/api/v1/videos` body to what a caller needs.
 *
 * Split out from the transport, and shared by the [InvidiousProbe] diagnostic and the
 * [InvidiousSource] resolver, so the two can never disagree about what an instance offered —
 * a probe that says "playable" while the resolver says "refused" would waste the very round trip
 * the probe exists to justify.
 *
 * An `error` field is honoured explicitly: an instance that is up but refusing answers with HTTP
 * 200 and `{"error":"..."}`, and reading that as "zero formats" would look identical to a track
 * nobody has.
 */
internal fun parseInvidiousVideo(body: String, json: Json): InvidiousVideoSummary {
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return InvidiousVideoSummary(error = "unparseable body (${body.take(60)})")

    root["error"].str()?.let { return InvidiousVideoSummary(error = it) }

    // `adaptiveFormats` carry the audio-only and video-only streams; `formatStreams` are
    // progressive and video+audio muxed. Both are considered, because an instance that offers
    // only muxed streams can still play audio — it just costs video-bandwidth to do it.
    val adaptive = (root["adaptiveFormats"] as? JsonArray).orEmpty()
    val progressive = (root["formatStreams"] as? JsonArray).orEmpty()
    val all = (adaptive + progressive).mapNotNull { it as? JsonObject }

    val audioOnly = all.filter { it["type"].str()?.startsWith("audio/", ignoreCase = true) == true }
    val muxed = all.filter { it["type"].str()?.startsWith("video/", ignoreCase = true) == true }

    // Prefer the highest-bitrate audio-only stream. Failing that, take the *lowest*-bitrate muxed
    // one: the video track is never rendered (no surface is attached in an audio session), so the
    // only thing its bitrate costs is bandwidth.
    val chosen = audioOnly.maxByOrNull { it.bitrate() } ?: muxed.minByOrNull { it.bitrate() }

    return InvidiousVideoSummary(
        title = root["title"].str(),
        author = root["author"].str(),
        lengthMs = root["lengthSeconds"].str()?.toLongOrNull()?.times(1000),
        audioFormats = audioOnly.size,
        totalFormats = all.size,
        bestAudioUrl = chosen?.get("url").str(),
        bestAudioMimeType = chosen?.get("type").str(),
        bestAudioBitrate = chosen?.bitrate()?.takeIf { it > 0 },
    )
}

/**
 * Builds a playable [AudioStream], or null when the instance named no URL.
 *
 * The expiry is read out of the URL itself. Invidious returns `googlevideo.com` URLs carrying an
 * `expire=<unix seconds>` parameter, so the stream's real lifetime is knowable rather than
 * guessed — which is what lets the resolver cache a resolved URL safely instead of handing the
 * player one that has already lapsed.
 */
internal fun InvidiousVideoSummary.toAudioStream(): AudioStream? {
    val url = bestAudioUrl ?: return null

    return AudioStream(
        url = url,
        mimeType = bestAudioMimeType.orEmpty(),
        bitrate = bestAudioBitrate,
        durationMs = lengthMs,
        expiresAtMs = expireEpochMs(url),
    )
}

private fun JsonObject.bitrate(): Int = get("bitrate").str()?.toIntOrNull() ?: 0

/**
 * Reads `expire` (unix seconds) out of a stream URL.
 *
 * Null when absent, which [AudioStream] treats as "no known expiry" rather than "expired" —
 * refusing a URL whose lifetime is merely unknown would break playback the moment a provider
 * changes its query parameters.
 */
private fun expireEpochMs(url: String): Long? =
    url.toHttpUrlOrNull()?.queryParameter("expire")?.toLongOrNull()?.times(1000)

/**
 * Collapses a message to a single line.
 *
 * An instance refusing a request answers with an nginx HTML page, newlines and all. In the probe
 * that broke the one-line-per-instance promise and split a single result across three log entries
 * on the device; here it would put a multi-line error inside a user-facing message.
 */
internal fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()
