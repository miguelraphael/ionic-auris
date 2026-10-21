package com.auris.app.data.remote.music

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Resolves audio through a list of public Invidious instances.
 *
 * ### Why a list, and why failover
 * As of 2026-10 only a couple of public instances have their API open; the rest answer `403
 * Endpoint disabled`, `401` or `forbidden`, and the trend is instances closing their APIs. A
 * resolver hardcoded to one host therefore breaks silently and permanently the day that volunteer
 * closes theirs. Trying the list in order, and remembering which hosts have just failed, turns
 * that into a recoverable event.
 *
 * ### Why the stream URL is probed before it is returned
 * An instance can answer with a `googlevideo.com` URL the client is not allowed to fetch. The URL
 * carries `ip=…` inside `sparams`, which binds it to whichever address the instance saw behind its
 * CDN; when that is not the caller's address, the CDN answers **403** — measured on the handset:
 * one track played, the next failed with `InvalidResponseCodeException: Response code: 403`, and
 * both had resolved successfully.
 *
 * Handing such a URL to ExoPlayer produces a user-visible failure that the user can only retry by
 * hand, and a *cached* dead URL reproduces the failure deterministically. So each candidate is
 * checked with a one-byte ranged `GET` before a stream is returned, and a refused preferred format
 * falls through to the next one the instance offered. The probe costs one round trip to the CDN,
 * not to the instance — and the instance, not the CDN, is the scarce resource here.
 *
 * When *every* format is refused the track is reported as a refusal rather than handed to the
 * player, because measurement showed the refusal is a property of the video, not of the format:
 * one refused track refused all ten of its URLs.
 *
 * ### Why the cache is safe here
 * The general rule in this codebase is that stream URLs are never cached, because InnerTube URLs
 * expire in about six hours and a stale one fails with a 403 that reads as a network error. That
 * rule is kept — but Invidious hands back URLs whose expiry is *in the URL*, so [InvidiousParser]
 * reads it and [AudioStream.isPlayable] enforces it; and a URL is only cached after [streamProbe]
 * has fetched a byte of it. What is cached is therefore a URL that is both unexpired and known to
 * be servable.
 *
 * Entries that have lapsed are dropped both when read (a hit is only a hit while playable) and
 * when the cache is trimmed, so a long browsing session cannot accumulate dead URLs.
 */
class InvidiousSource(
    private val httpClient: OkHttpClient,
    private val json: Json,
    /**
     * Read per call rather than captured, so an instance added in Settings takes effect on the
     * next track instead of the next launch.
     */
    private val instances: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whether a resolved stream URL can actually be fetched.
     *
     * Injected because the alternative — a real `googlevideo.com` request — is not something a
     * unit test should make, and because the failure being guarded against is a property of the
     * network, not of the URL.
     */
    private val streamProbe: (String) -> Boolean = { url -> probeStreamUrl(httpClient, url) },
) : MusicStreamResolver {

    /** Insertion-ordered so trimming has a defined victim. Guarded by its own monitor. */
    private val cache = LinkedHashMap<String, ResolvedAudio>()

    /** host -> epoch ms until which it is skipped. Guarded by its own monitor. */
    private val unhealthyUntil = mutableMapOf<String, Long>()

    override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> =
        withContext(Dispatchers.IO) {
            cached(videoId)?.let { return@withContext Result.success(it) }

            val configured = instances()
            if (configured.isEmpty()) {
                return@withContext Result.failure(IOException(NO_INSTANCES))
            }

            val failures = mutableListOf<String>()

            for (instance in ordered(configured)) {
                try {
                    val resolved = fetchResolved(instance, videoId)

                    // Reaching the instance at all counts as healthy, even when it answers
                    // "this track is unavailable" — that is an answer, not a failure.
                    markHealthy(instance)
                    // Only a confirmed-fetchable URL is stored; a refusal carries no stream.
                    if (resolved.stream != null) remember(videoId, resolved)

                    Log.i(TAG, "resolved $videoId via ${host(instance)} (stream=${resolved.stream != null})")
                    return@withContext Result.success(resolved)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    markUnhealthy(instance)
                    failures += "${host(instance)}: ${error.message ?: error::class.java.simpleName}"
                    Log.w(TAG, "instance ${host(instance)} failed for $videoId: ${error.message}")
                }
            }

            Result.failure(IOException("$NO_INSTANCES_WORK (${failures.joinToString("; ")})"))
        }

    /**
     * One instance's answer, reduced to something playable if anything in it is.
     *
     * Every offered format is probed before a stream is returned, because the CDN's refusal is a
     * property of the URL the instance minted for that format — and the instance offers up to
     * four of them. In practice that means a refused *best* format costs nothing but a one-byte
     * request, instead of becoming a player error the user cannot act on.
     *
     * There is deliberately **no second ask of the instance**: measured on the handset, a track
     * whose formats were refused was refused again on the next request, while each extra request
     * spends the instance's rate-limit budget (a burst of ~5, then `500`). Spending that budget on
     * a repeat costs every *other* track its chance to play.
     */
    private fun fetchResolved(instance: String, videoId: String): ResolvedAudio {
        val summary = fetchSummary(instance, videoId)

        // A refusal about the track: no URL to probe, and asking again would only repeat it.
        if (summary.error != null) return summary.into(null, summary.error)

        val candidates = summary.audioStreams
        if (candidates.isEmpty()) return summary.into(null, noStreamReason = null)

        val chosen = candidates.take(MAX_FORMAT_PROBES).firstOrNull { streamProbe(it.url) }
        if (chosen == null) {
            Log.w(
                TAG,
                "no fetchable format for $videoId from ${host(instance)} " +
                    "(${candidates.size} offered)",
            )
            return summary.into(null, UNFETCHABLE_REASON, UNFETCHABLE_STATUS)
        }

        return summary.into(chosen, noStreamReason = null)
    }

    /**
     * One instance's answer for one track.
     *
     * Throws only when the *instance* failed — unreachable, non-2xx, unparseable. A 200 carrying an
     * `error` is returned as-is: that is the instance's answer *about this track*, not a failure of
     * the instance, and treating it as one would bench a healthy host every time someone tapped a
     * track that no longer exists.
     */
    private fun fetchSummary(instance: String, videoId: String): InvidiousVideoSummary {
        val request = Request.Builder()
            .url("$instance/api/v1/videos/$videoId?region=$CONTENT_REGION")
            // Instances behind a browser-oriented front end serve HTML unless JSON is asked for
            // explicitly, and that failure then reads as a parse error rather than a status.
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} ${body.oneLine().take(80)}")
            }

            return parseInvidiousVideo(body, json)
        }
    }

    /**
     * Builds the result from a chosen stream, falling back to an honest refusal.
     *
     * Both arguments are passed in rather than derived, because "the instance says the video is
     * gone", "the instance offered no audio format" and "the instance offered formats but none can
     * be fetched" are different answers that all arrive here with no stream.
     */
    private fun InvidiousVideoSummary.into(
        stream: AudioStream?,
        noStreamReason: String?,
        status: String = REFUSAL_STATUS,
    ): ResolvedAudio {
        if (stream == null) {
            return ResolvedAudio(
                refusal = StreamRefusalInfo(
                    status = status,
                    reason = noStreamReason ?: NO_FORMATS_REASON,
                ),
                title = title,
                author = author,
                lengthMs = lengthMs,
            )
        }

        return ResolvedAudio(
            stream = stream,
            title = title,
            author = author,
            lengthMs = lengthMs,
        )
    }

    // ---- Cache --------------------------------------------------------------

    /** A cached result, but only while its stream is still usable. */
    private fun cached(videoId: String): ResolvedAudio? = synchronized(cache) {
        val hit = cache[videoId] ?: return null
        if (hit.stream?.isPlayable(clock()) != true) {
            cache.remove(videoId)
            return null
        }
        hit
    }

    /**
     * Stores a resolved stream and trims the cache.
     *
     * Expired entries are evicted before live ones, so eviction pressure falls on URLs that were
     * already useless rather than on the ones a back-navigation is about to want.
     */
    private fun remember(videoId: String, resolved: ResolvedAudio) = synchronized(cache) {
        cache[videoId] = resolved
        if (cache.size <= CACHE_LIMIT) return

        val entries = cache.entries.iterator()
        while (cache.size > CACHE_LIMIT && entries.hasNext()) {
            if (entries.next().value.stream?.isPlayable(clock()) != true) entries.remove()
        }
        // Still over: drop the oldest insertions.
        while (cache.size > CACHE_LIMIT) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    // ---- Health -------------------------------------------------------------

    /**
     * Config order, with recently-failed instances moved to the back.
     *
     * `sortedBy` is stable, so the user's priority is preserved within each group, and a host that
     * has only *just* failed is still tried once everything else has been — being slightly slow is
     * better than refusing to play because every candidate was benched.
     */
    private fun ordered(all: List<String>): List<String> {
        val now = clock()
        return all.sortedBy { if (isCoolingDown(it, now)) 1 else 0 }
    }

    private fun isCoolingDown(instance: String, now: Long): Boolean =
        synchronized(unhealthyUntil) { (unhealthyUntil[instance] ?: 0L) > now }

    private fun markUnhealthy(instance: String) = synchronized(unhealthyUntil) {
        unhealthyUntil[instance] = clock() + UNHEALTHY_COOLDOWN_MS
    }

    private fun markHealthy(instance: String) = synchronized(unhealthyUntil) {
        unhealthyUntil.remove(instance)
    }

    private fun host(instance: String): String = instance.substringAfter("://").trimEnd('/')

    companion object {
        /**
         * How long a failed instance is deprioritised.
         *
         * Short on purpose. The failure mode this guards against is a dead host first in the
         * list costing every playback a connect timeout; it is not a punishment, and instances
         * recover (a rate limit lifts, a restart completes). Two minutes spends one extra round
         * trip on recovery without making a dead host expensive the rest of the session.
         */
        const val UNHEALTHY_COOLDOWN_MS = 2 * 60_000L

        /** Resolved URLs retained. Small: each is one track's worth of data. */
        const val CACHE_LIMIT = 64

        private const val REFUSAL_STATUS = "UNPLAYABLE"
        private const val NO_FORMATS_REASON = "The instance returned no playable audio format"

        /**
         * Content region, passed to Invidious as `region`.
         *
         * The instance applies it as YouTube's `gl`, so the resolver's idea of "available" matches
         * the catalogue's. Kept equal to [InnerTubeClient.CONTENT_COUNTRY] deliberately: browsing
         * one country's catalogue and resolving against another produces tracks that appear and
         * then refuse.
         */
        private val CONTENT_REGION = InnerTubeClient.CONTENT_COUNTRY

        /**
         * How many of the offered formats to probe.
         *
         * Four: enough to cross from audio-only to muxed, few enough that a track nobody can
         * fetch is reported in about a second. Measurement on the handset showed a refused track
         * refusing **all ten** of its URLs, so a longer probe would only add latency.
         */
        private const val MAX_FORMAT_PROBES = 4

        /*
         * Not phrased as a content problem: the track exists and the instance named it. What
         * failed is delivery to *this* device, which is why the wording says so and why
         * [com.auris.app.ui.music.StreamRefusal] must not call it unavailable.
         */
        private const val UNFETCHABLE_REASON =
            "the instance offered only streams this device was not allowed to fetch"
        private const val UNFETCHABLE_STATUS = "UNFETCHABLE"
        private const val NO_INSTANCES = "No Invidious instance is configured"
        private const val NO_INSTANCES_WORK = "No Invidious instance could serve this track"
    }
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

private const val TAG = "AurisMusic"

/**
 * Whether [url] will actually serve audio.
 *
 * A ranged `GET` rather than a `HEAD`: the thing being tested is the request the player will make,
 * and CDNs do not always treat the two the same. One byte is enough to tell a `200`/`206` from a
 * `403`, and it is not a download.
 *
 * Unreachable counts as unusable. A URL that cannot be connected to is no better than one that is
 * refused, and both are cheaper to replace than to hand to the player.
 */
private fun probeStreamUrl(client: OkHttpClient, url: String): Boolean = try {
    val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-0")
        .header("User-Agent", USER_AGENT)
        .build()

    client.newCall(request).execute().use { response ->
        val usable = response.code == 206 || response.code == 200
        if (!usable) Log.i(TAG, "stream url rejected: HTTP ${response.code}")
        usable
    }
} catch (error: IOException) {
    Log.i(TAG, "stream url unreachable: ${error.message}")
    false
}
