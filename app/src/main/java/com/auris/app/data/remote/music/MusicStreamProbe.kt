package com.auris.app.data.remote.music

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One client identity's answer to an **unauthenticated** `/player` request.
 *
 * Deliberately carries the raw decision — HTTP status, `playabilityStatus`, and the format counts
 * — rather than a verdict, because the verdict is the thing under investigation.
 */
data class StreamProbeResult(
    val clientName: String,
    val clientVersion: String,
    val httpStatus: Int? = null,
    val playabilityStatus: String? = null,
    val reason: String? = null,
    val audioFormats: Int = 0,
    val totalFormats: Int = 0,
    val title: String? = null,
    val error: String? = null,
) {
    /**
     * One terse line, because this is read on a phone screen and then pasted back verbatim.
     *
     * `title` is included on purpose: when `/player` answers `UNPLAYABLE` but still names the
     * track, the request was well-formed and the *server* declined — which is a different bug
     * from one that fails to reach the right video at all.
     */
    fun report(): String = buildString {
        append(clientName)
        append(" http=").append(httpStatus?.toString() ?: "-")
        append(" status=").append(playabilityStatus ?: if (error != null) "ERR" else "-")
        append(" audio=").append(audioFormats)
        append(" forms=").append(totalFormats)
        append(" title=").append(title?.take(28)?.let { "\"$it\"" } ?: "-")
        reason?.let { append(" reason=\"").append(it).append("\"") }
        error?.let { append(" error=\"").append(it).append("\"") }
    }
}

/**
 * Probes whether an unauthenticated `/player` will serve audio **from this device's network**.
 *
 * ### Why this exists
 * The same request fails from a datacenter IP with `UNPLAYABLE` even when carrying a genuine,
 * freshly-minted PO token (see `ARCHITECTURE.md` §4, *Verified 2026-10*). YouTube's refusal of
 * unauthenticated `/player` is frequently **IP-reputation** driven, so a server-side result does
 * not settle what a handset sees. This runs the identical call from the phone and reports what
 * actually came back, across every client identity worth trying.
 *
 * ### It is a diagnostic, not a feature
 * Nothing routes playback through this. It is read-only, sends no credentials, and exists to
 * answer one question before any resolution code is written: *is the wall attestation, or is it
 * this network?* Delete it once that is known.
 *
 * Emits one [StreamProbeResult] per client as it completes, so a slow or hanging identity cannot
 * hold up the rest of the report.
 */
class MusicStreamProbe(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * Runs the probe, emitting a result per client identity.
     *
     * A `visitorData` is obtained first, because that is what a real client sends and its absence
     * was already ruled out as the cause — leaving it in keeps this comparable to the app's own
     * request path.
     */
    fun probe(videoId: String = PROBE_VIDEO_ID): Flow<StreamProbeResult> = flow {
        val visitorData = withContext(Dispatchers.IO) { fetchVisitorData() }
        Log.i(
            TAG,
            "probe start: videoId=$videoId visitorData=${if (visitorData.isNullOrBlank()) "MISSING" else "ok"}",
        )

        CLIENTS.forEach { client ->
            val result = withContext(Dispatchers.IO) { runClient(client, videoId, visitorData) }
            Log.i(TAG, "probe ${result.report()}")
            emit(result)
        }

        Log.i(TAG, "probe done")
    }

    /** Fetches a `visitorData`, or null. Never throws: its absence is a data point, not a failure. */
    private fun fetchVisitorData(): String? = runCatching {
        val body = buildJsonObject {
            put(
                "context",
                buildJsonObject {
                    put(
                        "client",
                        buildJsonObject {
                            put("clientName", "WEB")
                            put("clientVersion", WEB_CLIENT_VERSION)
                            put("hl", "en")
                            put("gl", "US")
                        },
                    )
                },
            )
            put("engagementType", "ENGAGEMENT_TYPE_UNBOUND")
        }

        val (status, text) = post(
            endpoint = "att/get",
            body = json.encodeToString(JsonObject.serializer(), body),
            clientNumber = "1",
            clientVersion = WEB_CLIENT_VERSION,
            visitorData = null,
        )
        if (status != 200) return@runCatching null

        val root = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        (root["responseContext"] as? JsonObject)?.get("visitorData").str()
    }.getOrNull()

    private fun runClient(client: ProbeClient, videoId: String, visitorData: String?): StreamProbeResult =
        runCatching {
            val body = buildJsonObject {
                put(
                    "context",
                    buildJsonObject {
                        put(
                            "client",
                            buildJsonObject {
                                put("clientName", client.name)
                                put("clientVersion", client.version)
                                put("hl", "en")
                                put("gl", "US")
                                if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
                                client.extraClient.forEach { (key, value) -> put(key, value) }
                            },
                        )
                    },
                )
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                client.extraBody.forEach { (key, value) -> put(key, value) }
            }

            val (status, text) = post(
                endpoint = "player",
                body = json.encodeToString(JsonObject.serializer(), body),
                clientNumber = client.number,
                clientVersion = client.version,
                visitorData = visitorData,
            )
            parseStreamProbe(client.name, client.version, status, text, json)
        }.getOrElse { error ->
            StreamProbeResult(
                clientName = client.name,
                clientVersion = client.version,
                error = error.message ?: error::class.java.simpleName,
            )
        }

    /**
     * One raw POST.
     *
     * Returns the status alongside the body rather than throwing on a non-2xx, because "which
     * status did it return" is the measurement.
     */
    private fun post(
        endpoint: String,
        body: String,
        clientNumber: String,
        clientVersion: String,
        visitorData: String?,
    ): Pair<Int, String> {
        val builder = Request.Builder()
            .url("$BASE_URL/youtubei/v1/$endpoint?prettyPrint=false")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "*/*")
            // YouTube serves HTML to clients that do not identify themselves.
            .header("User-Agent", USER_AGENT)
            .header("X-Goog-Api-Format-Version", "1")
            .header("X-YouTube-Client-Name", clientNumber)
            .header("X-YouTube-Client-Version", clientVersion)

        if (!visitorData.isNullOrBlank()) builder.header("X-Goog-Visitor-Id", visitorData)

        val response = httpClient.newCall(builder.build()).execute()
        return response.use { it.code to it.body?.string().orEmpty() }
    }

    /** A client identity to try. Extras are raw JSON so a client can add fields without a model. */
    private data class ProbeClient(
        val name: String,
        val number: String,
        val version: String,
        val extraClient: Map<String, JsonElement> = emptyMap(),
        val extraBody: Map<String, JsonElement> = emptyMap(),
    )

    private companion object {
        const val BASE_URL = "https://music.youtube.com"
        const val WEB_CLIENT_VERSION = "2.20240101.00.00"

        /**
         * A track that is unambiguously available worldwide.
         *
         * Chosen so a refusal cannot be blamed on the video being private, region-locked or
         * removed — which is the whole risk when probing a single id.
         */
        const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"

        /**
         * The identities worth distinguishing.
         *
         * `WEB_REMIX` is what the app uses; the rest are the identities that historically served
         * unauth audio, and the two embedded ones now announce their own deprecation, which is
         * itself worth capturing verbatim.
         */
        val CLIENTS = listOf(
            ProbeClient("WEB_REMIX", "67", "1.20240101.01.00"),
            ProbeClient("WEB", "1", WEB_CLIENT_VERSION),
            ProbeClient("MWEB", "2", WEB_CLIENT_VERSION),
            ProbeClient(
                "ANDROID_MUSIC", "21", "7.27.52",
                extraClient = mapOf("androidSdkVersion" to JsonPrimitive(34)),
            ),
            ProbeClient(
                "ANDROID", "3", "19.29.37",
                extraClient = mapOf("androidSdkVersion" to JsonPrimitive(34)),
            ),
            ProbeClient(
                "IOS", "5", "19.29.1",
                extraClient = mapOf(
                    "deviceMake" to JsonPrimitive("Apple"),
                    "deviceModel" to JsonPrimitive("iPhone16,2"),
                    "osName" to JsonPrimitive("iPhone"),
                    "osVersion" to JsonPrimitive("18.1.0.22B83"),
                ),
            ),
            ProbeClient(
                "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "85", "2.0",
                extraBody = mapOf(
                    "thirdParty" to buildJsonObject { put("embedUrl", "https://www.youtube.com/") },
                ),
            ),
            ProbeClient(
                "WEB_EMBEDDED_PLAYER", "56", "1.20240101.00.00",
                extraBody = mapOf(
                    "thirdParty" to buildJsonObject { put("embedUrl", "https://www.youtube.com/") },
                ),
            ),
        )

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val USER_AGENT = "com.google.android.youtube/19.29.37 (Linux; U; Android 14)"
        private const val TAG = "AurisMusicProbe"
    }
}

/**
 * Reduces a `/player` body to what the probe reports.
 *
 * Split out from the transport so it can be tested against captured bodies — the counts and the
 * `playabilityStatus` are the entire signal, and they are read by hand when this is run on a
 * device, so getting them wrong would waste the round trip.
 */
internal fun parseStreamProbe(
    clientName: String,
    clientVersion: String,
    httpStatus: Int,
    body: String,
    json: Json,
): StreamProbeResult {
    val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: return StreamProbeResult(
            clientName = clientName,
            clientVersion = clientVersion,
            httpStatus = httpStatus,
            error = "unparseable body (${body.take(60)})",
        )

    val playability = root["playabilityStatus"] as? JsonObject
    val streaming = root["streamingData"] as? JsonObject

    // Both arrays are counted: a client that serves progressive formats instead of adaptive ones
    // would otherwise be reported as "no formats" and look identical to a refusal.
    val formats = (streaming?.get("adaptiveFormats") as? JsonArray).orEmpty() +
        (streaming?.get("formats") as? JsonArray).orEmpty()

    val audio = formats.count { format ->
        val mime = (format as? JsonObject)?.get("mimeType").str()
        mime?.startsWith("audio/", ignoreCase = true) == true
    }

    return StreamProbeResult(
        clientName = clientName,
        clientVersion = clientVersion,
        httpStatus = httpStatus,
        playabilityStatus = playability?.get("status").str(),
        reason = playability?.get("reason").str(),
        audioFormats = audio,
        totalFormats = formats.size,
        title = (root["videoDetails"] as? JsonObject)?.get("title").str(),
    )
}

/** Reads a JSON scalar as text, tolerating absent keys and non-scalar values. */
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
