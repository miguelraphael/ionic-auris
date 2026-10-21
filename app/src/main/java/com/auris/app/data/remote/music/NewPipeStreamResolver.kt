package com.auris.app.data.remote.music

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream as NewPipeAudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

/**
 * Resolves audio with [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).
 *
 * ### Why this exists next to [InvidiousSource]
 * The instance route fails on roughly half a measured catalogue, and the failures are not random:
 * the instance returns stream URLs whose `n` parameter has not been descrambled, so the CDN
 * refuses them with `403` from *this* device. Fixing that needs a JavaScript engine and YouTube's
 * player script — which is precisely what NewPipeExtractor ships, and what a public instance does
 * not reliably do.
 *
 * Extraction therefore happens on the device, against the same network the player will use, so the
 * URLs it mints are addressed to this egress rather than to a volunteer's.
 *
 * ### Licence
 * NewPipeExtractor is **GPL-3.0-or-later**. Linking it makes the whole application a covered work
 * under the same licence whenever it is distributed; see `LICENSE` and `ARCHITECTURE.md` §4.
 *
 * ### Cost
 * Extraction is much heavier than calling an API: a cold resolve downloads and runs the player
 * script, which takes seconds on a phone. Successful results are therefore cached for their
 * lifetime, exactly like [InvidiousSource]'s — which also absorbs the fact that the ViewModel
 * resolves a tapped track twice (once to learn its title, once to build the queue).
 */
class NewPipeStreamResolver(
    private val httpClient: OkHttpClient,
    country: String = InnerTubeClient.CONTENT_COUNTRY,
    language: String = "en",
) : MusicStreamResolver {

    init {
        // NewPipeExtractor keeps its downloader and localization in process-global state, so this
        // is a one-time setup; the guard lives in the companion object.
        ensureInitialised(httpClient, Localization(language, country), ContentCountry(country))
    }

    override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> =
        withContext(Dispatchers.IO) {
            cached(videoId)?.let { return@withContext Result.success(it) }

            try {
                val info = StreamInfo.getInfo(ServiceList.YouTube, watchUrl(videoId))
                val resolved = info.into(videoId)

                // Only a confirmed-fetchable URL is cached; a refusal would otherwise be replayed
                // for as long as the track stayed in the cache.
                if (resolved.stream != null) remember(videoId, resolved)

                Log.i(
                    TAG,
                    if (resolved.stream != null) {
                        "resolved $videoId via NewPipe (${resolved.stream.mimeType})"
                    } else {
                        "NewPipe declined $videoId (${resolved.refusal?.status}: " +
                            "${resolved.refusal?.reason})"
                    },
                )
                Result.success(resolved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A per-track answer from the extractor is still an answer, and worth more than a
                // generic "could not reach a source": it tells the user to pick a different song.
                refusalFor(error)?.let { return@withContext Result.success(it) }

                Log.w(TAG, "NewPipe failed for $videoId: ${error.message}")
                Result.failure(
                    IOException("NewPipe: ${error.message ?: error::class.java.simpleName}", error),
                )
            }
        }

    /**
     * Turns one extractor answer into a result.
     *
     * The stream is probed before it is returned for the same reason [InvidiousSource] probes:
     * a URL that cannot be fetched is a player error the user has no way to act on, while a
     * refusal with [StreamRefusalInfo.UNFETCHABLE] lets the fallback resolver try elsewhere.
     */
    private fun StreamInfo.into(videoId: String): ResolvedAudio {
        val audio = chooseAudio()
        val url = audio?.url

        if (audio == null || url.isNullOrBlank()) {
            Log.w(TAG, "NewPipe returned ${audioStreams.size} audio streams without a URL")
            return refusalResult(StreamRefusalInfo(REFUSAL_STATUS, NO_AUDIO_REASON))
        }

        if (!probeStreamUrl(httpClient, url)) {
            Log.w(TAG, "NewPipe's stream URL was refused by the CDN (${url.take(60)}…)")
            return refusalResult(
                StreamRefusalInfo(StreamRefusalInfo.UNFETCHABLE, UNFETCHABLE_REASON),
            )
        }

        return ResolvedAudio(
            stream = AudioStream(
                url = url,
                mimeType = audio.format?.mimeType ?: MIME_MP4,
                bitrate = audio.selectionBitrate(),
                durationMs = duration.takeIf { it > 0 }?.times(1000),
                expiresAtMs = expireEpochMs(url),
            ),
            title = name?.takeIf { it.isNotBlank() } ?: videoId,
            author = uploaderName?.takeIf { it.isNotBlank() },
            lengthMs = duration.takeIf { it > 0 }?.times(1000),
        )
    }

    private fun StreamInfo.refusalResult(refusal: StreamRefusalInfo): ResolvedAudio =
        ResolvedAudio(
            refusal = refusal,
            title = name?.takeIf { it.isNotBlank() },
            author = uploaderName?.takeIf { it.isNotBlank() },
            lengthMs = duration.takeIf { it > 0 }?.times(1000),
        )

    /**
     * Picks the stream to hand the player.
     *
     * AAC in MP4 is preferred over Opus in WebM even when the Opus stream is larger: it needs no
     * container-specific extractor work on the playback side, and it is what [InvidiousSource]
     * already plays on this device. Highest bitrate wins within that preference, so the choice is
     * still the best available.
     */
    private fun StreamInfo.chooseAudio(): NewPipeAudioStream? {
        val usable = audioStreams.filter { !it.url.isNullOrBlank() }
        return usable.filter { it.format?.mimeType == MIME_MP4 }.maxByOrNull { it.selectionBitrate() }
            ?: usable.maxByOrNull { it.selectionBitrate() }
    }

    /** Extractor bitrates are optional; `averageBitrate` is `-1` when it is not reported. */
    private fun NewPipeAudioStream.selectionBitrate(): Int =
        averageBitrate.takeIf { it > 0 } ?: bitrate

    private fun cached(videoId: String): ResolvedAudio? = synchronized(cache) {
        val hit = cache[videoId] ?: return null
        if (hit.isPlayable) return hit
        cache.remove(videoId)
        null
    }

    private fun remember(videoId: String, resolved: ResolvedAudio) = synchronized(cache) {
        cache[videoId] = resolved
        while (cache.size > CACHE_ENTRIES) {
            cache.keys.firstOrNull()?.let(cache::remove)
        }
    }

    private companion object {
        private const val TAG = "AurisMusic"

        private const val CACHE_ENTRIES = 8
        private const val REFUSAL_STATUS = "UNPLAYABLE"
        private const val MIME_MP4 = "audio/mp4"

        private const val NO_AUDIO_REASON =
            "the extractor named this track but returned no audio stream"

        /*
         * Phrased as delivery, not content: the track exists and was found, it just cannot be
         * fetched from here. The wording in StreamRefusal depends on that distinction.
         */
        private const val UNFETCHABLE_REASON =
            "the extractor offered only streams this device was not allowed to fetch"

        private val cache = LinkedHashMap<String, ResolvedAudio>()

        @Volatile
        private var initialised = false

        private val initLock = Any()

        /** [NewPipe] holds its downloader and localization in static fields. */
        private fun ensureInitialised(
            client: OkHttpClient,
            localization: Localization,
            contentCountry: ContentCountry,
        ) {
            if (initialised) return
            synchronized(initLock) {
                if (initialised) return
                NewPipe.init(OkHttpDownloader(client), localization, contentCountry)
                initialised = true
            }
        }

        private fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

        /**
         * Maps the extractor's per-track exceptions to refusals.
         *
         * These all mean "this video cannot be played" — private, removed, age-restricted,
         * region-blocked, members-only, terminated. Everything else (IO, parsing, reCaptcha) is a
         * failure of extraction itself and must not be reported as content being gone.
         */
        private fun refusalFor(error: Exception): ResolvedAudio? = when (error) {
            is ContentNotAvailableException,
            is ContentNotSupportedException,
            is AccountTerminatedException,
            -> ResolvedAudio(
                refusal = StreamRefusalInfo(
                    status = REFUSAL_STATUS,
                    reason = error.message?.takeIf { it.isNotBlank() } ?: CONTENT_REASON,
                ),
            )

            else -> null
        }

        private const val CONTENT_REASON = "this track cannot be played"
    }
}

/**
 * Adapts the app's OkHttp client to the extractor's downloader interface.
 *
 * A separate class because the extractor reaches for the downloader through a static field: it is
 * not something a resolver can pass per call. Headers are forwarded verbatim, which is what keeps
 * the extractor's client identity and language headers intact.
 */
internal class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val contentType = request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull()
        val body = request.dataToSend()?.toRequestBody(contentType)

        val outgoing = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .apply {
                request.headers().forEach { (name, values) ->
                    values.forEach { value -> addHeader(name, value) }
                }
            }
            .build()

        client.newCall(outgoing).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}
