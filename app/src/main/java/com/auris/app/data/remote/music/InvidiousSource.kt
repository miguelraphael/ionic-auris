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
 * As of 2026-10 **exactly one** public instance has its API open; the others answer `403 Endpoint
 * disabled`, `401` or `forbidden`, and the trend is instances closing their APIs. A resolver
 * hardcoded to one host therefore breaks silently and permanently the day that volunteer closes
 * theirs. Trying the list in order, and remembering which hosts have just failed, turns that into
 * a recoverable event.
 *
 * ### Why the cache is safe here
 * The general rule in this codebase is that stream URLs are never cached, because InnerTube URLs
 * expire in about six hours and a stale one fails with a 403 that reads as a network error. That
 * rule is kept — but Invidious hands back `googlevideo.com` URLs whose expiry is *in the URL*, so
 * [InvidiousParser] reads it and [AudioStream.isPlayable] enforces it. What is cached is therefore
 * a URL with a known deadline, never an unexamined one.
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
                    val resolved = fetch(instance, videoId)

                    // Reaching the instance at all counts as healthy, even when it answers
                    // "this track is unavailable" — that is an answer, not a failure.
                    markHealthy(instance)
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
     * One instance's answer for one track.
     *
     * Throws only when the *instance* failed — unreachable, non-2xx, unparseable. A refusal about
     * the track is returned as a [ResolvedAudio] so it does not retire a working instance.
     */
    private fun fetch(instance: String, videoId: String): ResolvedAudio {
        val request = Request.Builder()
            .url("$instance/api/v1/videos/$videoId")
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

            val summary = parseInvidiousVideo(body, json)

            // A 200 carrying an `error` is the instance's answer *about this track*, not a
            // failure of the instance. Treating it as one would bench a healthy host every time
            // someone tapped a track that no longer exists.
            return summary.into(noStreamReason = summary.error)
        }
    }

    /**
     * Builds the result, preferring the stream and falling back to an honest refusal.
     *
     * The reason is passed in rather than derived, because "the instance says the video is gone"
     * and "the instance offered no audio format" are different answers that both arrive here with
     * no stream.
     */
    private fun InvidiousVideoSummary.into(noStreamReason: String?): ResolvedAudio {
        val stream = if (noStreamReason == null) toAudioStream() else null

        if (stream == null) {
            return ResolvedAudio(
                refusal = StreamRefusalInfo(
                    status = REFUSAL_STATUS,
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
        private const val NO_INSTANCES = "No Invidious instance is configured"
        private const val NO_INSTANCES_WORK = "No Invidious instance could serve this track"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        private const val TAG = "AurisMusic"
    }
}
