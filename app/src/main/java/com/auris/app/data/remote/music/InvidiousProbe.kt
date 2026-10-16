package com.auris.app.data.remote.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * One Invidious instance's answer to the question "can this device actually play audio from you".
 *
 * Deliberately records the **stream** separately from the API response. An instance can answer
 * `/api/v1/videos` with a `200` and a full format list and still hand back a URL that delivers no
 * bytes to this device — because the URL is bound to the instance's IP, or has already expired.
 * Those are different failures and only the second one decides whether playback works.
 */
data class InvidiousProbeResult(
    /** Host only, e.g. `invidious.f5.si`, because that is all that fits on a phone screen. */
    val instance: String,
    val httpStatus: Int? = null,
    val title: String? = null,
    val audioFormats: Int = 0,
    val totalFormats: Int = 0,
    /** HTTP status of a ranged GET on the resolved audio URL, following redirects. */
    val streamHttpStatus: Int? = null,
    /** Bytes actually received from that ranged GET. Zero is the failure that matters. */
    val streamBytes: Long = 0,
    val error: String? = null,
    val streamError: String? = null,
) {
    /**
     * One terse line, because this is read on a phone screen and pasted back verbatim.
     *
     * `stream=` is the verdict: `206/65536` means real audio arrived, `-` means the instance
     * offered no URL at all, and `ERR` plus `streamError` means it offered one that did not
     * deliver. Only the first supports building a resolver on this instance.
     */
    fun report(): String = buildString {
        append(instance)
        append(" http=").append(httpStatus?.toString() ?: "-")
        append(" audio=").append(audioFormats)
        append(" forms=").append(totalFormats)
        append(" stream=").append(
            when {
                streamError != null -> "ERR"
                streamHttpStatus != null -> "$streamHttpStatus/$streamBytes"
                else -> "-"
            },
        )
        append(" title=").append(title?.take(28)?.let { "\"$it\"" } ?: "-")
        error?.let { append(" error=\"").append(it.take(60)).append("\"") }
        streamError?.let { append(" streamError=\"").append(it.take(60)).append("\"") }
    }
}

/**
 * Probes whether a public Invidious instance will serve **playable audio to this device**.
 *
 * ### Why this exists
 * The first-party route is closed: an unauthenticated InnerTube `/player` refuses on any network,
 * including this handset's own (`ARCHITECTURE.md` §4, *Verified 2026-10*). Invidious works
 * *server-side* — its instances solve the attestation we could not — so it is the remaining route
 * to real YouTube Music audio without credentials.
 *
 * But every result so far was gathered from a datacenter, and the one question that has burned
 * this project twice is whether a server-side success survives the handset's own network. So this
 * runs the identical call **from the phone**, and then does the part a status code cannot: it
 * follows the returned URL and confirms audio bytes actually arrive.
 *
 * ### It is a diagnostic, not a feature
 * Nothing routes playback through this. It is read-only, sends no credentials and stores nothing.
 *
 * Emits one [InvidiousProbeResult] per instance as it completes, so one unreachable instance
 * cannot hold up the report.
 */
class InvidiousProbe(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Overridable so a future settings screen can probe a user-supplied list. */
    private val instances: List<String> = DEFAULT_INSTANCES,
) {

    /** Runs the probe, emitting a result per instance. */
    fun probe(videoId: String = PROBE_VIDEO_ID): Flow<InvidiousProbeResult> = flow {
        Log.i(TAG, "probe start: videoId=$videoId instances=${instances.size}")

        instances.forEach { baseUrl ->
            val result = withContext(Dispatchers.IO) { probeInstance(baseUrl, videoId) }
            Log.i(TAG, "probe ${result.report()}")
            emit(result)
        }

        Log.i(TAG, "probe done")
    }

    private fun probeInstance(baseUrl: String, videoId: String): InvidiousProbeResult {
        val host = baseUrl.substringAfter("://").trimEnd('/')

        return runCatching {
            val (status, text) = get("$baseUrl/api/v1/videos/$videoId", acceptJson = true)
            if (status != 200) {
                return@runCatching InvidiousProbeResult(
                    instance = host,
                    httpStatus = status,
                    error = text.trim().take(80).ifBlank { "HTTP $status" },
                )
            }

            val summary = parseInvidiousVideo(text, json)
            if (summary.error != null) {
                return@runCatching InvidiousProbeResult(
                    instance = host,
                    httpStatus = status,
                    title = summary.title,
                    audioFormats = summary.audioFormats,
                    totalFormats = summary.totalFormats,
                    error = summary.error,
                )
            }

            // The decisive step: the instance named an audio URL, but only a ranged GET proves
            // this device can pull bytes from it.
            val streamUrl = summary.bestAudioUrl
            val stream = if (streamUrl.isNullOrBlank()) {
                StreamCheck()
            } else {
                checkStream(streamUrl)
            }

            InvidiousProbeResult(
                instance = host,
                httpStatus = status,
                title = summary.title,
                audioFormats = summary.audioFormats,
                totalFormats = summary.totalFormats,
                streamHttpStatus = stream.status,
                streamBytes = stream.bytes,
                streamError = stream.error,
            )
        }.getOrElse { error ->
            InvidiousProbeResult(
                instance = host,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    /**
     * Pulls a slice of the resolved audio URL.
     *
     * The range matters. Invidious hands back `googlevideo.com` URLs that first answer with a
     * `302` to a CDN node; OkHttp follows that, and only then does audio arrive. A plain HEAD, or
     * a request that stops at the redirect, would report success for a stream that never plays.
     */
    private fun checkStream(url: String): StreamCheck = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-$RANGE_END")
            .build()

        // The default redirect policy is what makes the CDN hop transparent; it is left on.
        httpClient.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()?.size?.toLong() ?: 0L
            StreamCheck(status = response.code, bytes = bytes)
        }
    }.getOrElse { error ->
        StreamCheck(error = error.message ?: error::class.java.simpleName)
    }

    /** One raw GET. Returns the status alongside the body so a non-2xx is a measurement, not a throw. */
    private fun get(url: String, acceptJson: Boolean): Pair<Int, String> {
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        // Instances behind a browser-oriented front end serve HTML to clients that do not ask
        // for JSON explicitly, and the failure then reads as a parse error rather than a status.
        if (acceptJson) builder.header("Accept", "application/json")

        return httpClient.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() }
    }

    private data class StreamCheck(
        val status: Int? = null,
        val bytes: Long = 0,
        val error: String? = null,
    )

    companion object {
        /**
         * Instances to probe.
         *
         * The first is the only public instance with its API open as of 2026-10; the other two
         * are listed with the API **closed** (`403 Endpoint disabled`, `401`) on purpose — the
         * contrast is the evidence that this route depends on a single volunteer's server, and a
         * device that suddenly reaches one of them would be worth knowing about.
         */
        val DEFAULT_INSTANCES = listOf(
            "https://invidious.f5.si",
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
        )

        /**
         * A track that is unambiguously available worldwide.
         *
         * Chosen so a refusal cannot be blamed on the video being private, region-locked or
         * removed — the whole risk when probing a single id.
         */
        const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"

        private const val RANGE_END = 65535L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val TAG = "AurisInvidiousProbe"
    }
}

/**
 * What an `/api/v1/videos/{id}` body reduces to.
 *
 * [bestAudioUrl] is the highest-bitrate audio-only format's URL. Audio-only is preferred because
 * playing a muxed video format for a music app wastes an order of magnitude of bandwidth — the
 * same reasoning as [InnerTubeParser].
 */
internal data class InvidiousVideoSummary(
    val title: String? = null,
    val audioFormats: Int = 0,
    val totalFormats: Int = 0,
    val bestAudioUrl: String? = null,
    val error: String? = null,
)

/**
 * Reduces a `/api/v1/videos` body to what the probe reports.
 *
 * Split out from the transport so it can be tested against captured bodies — the counts and the
 * chosen URL are the entire signal, and they are read by hand off a phone, so getting them wrong
 * would waste the round trip.
 *
 * An `error` field is honoured explicitly: an instance that is up but refusing returns
 * `{"error":"..."}` with HTTP 200, and reading that as "zero formats" would look identical to a
 * track nobody has.
 */
internal fun parseInvidiousVideo(body: String, json: Json): InvidiousVideoSummary {
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return InvidiousVideoSummary(error = "unparseable body (${body.take(60)})")

    root["error"].str()?.let { return InvidiousVideoSummary(error = it) }

    // `adaptiveFormats` carry the audio-only streams. `formatStreams` are progressive and
    // video+audio muxed; they are counted for the total so a response that only offers them
    // does not read as an empty failure, but they are not what playback should use.
    val adaptive = (root["adaptiveFormats"] as? JsonArray).orEmpty()
    val progressive = (root["formatStreams"] as? JsonArray).orEmpty()
    val all = adaptive + progressive

    val audio = all.mapNotNull { it as? JsonObject }.filter { format ->
        format["type"].str()?.startsWith("audio/", ignoreCase = true) == true
    }

    // Highest bitrate wins; a null bitrate loses to any known one but still beats nothing.
    val best = audio.maxByOrNull { format -> format["bitrate"].str()?.toIntOrNull() ?: 0 }

    return InvidiousVideoSummary(
        title = root["title"].str(),
        audioFormats = audio.size,
        totalFormats = all.size,
        bestAudioUrl = best?.get("url").str(),
    )
}
