package com.auris.app.data.remote.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Finds official cover art for a track.
 *
 * Deliberately separate from [MusicSource]'s playback half: artwork is metadata, it is
 * best-effort, and a failure must never prevent a track from playing.
 */
fun interface AlbumArtSource {
    /** Square cover art URL, or null when nothing confident was found. */
    suspend fun artworkFor(title: String, artist: String?): String?
}

/**
 * Cover art from the public iTunes Search API.
 *
 * ### Why not just use the video thumbnail
 * YouTube thumbnails are frames from a music video — 16:9, letterboxed into a 4:3 canvas on
 * `hqdefault`, often with titles burned in. Even for auto-generated label uploads, where the
 * thumbnail *is* the cover, it is 480px wide and cropped. The Search API answers with the real
 * square release artwork, which is what a music player is expected to show.
 *
 * ### Why matching is done here rather than trusting the first result
 * The query is a video title, not a tag: "Miley Cyrus - Flowers (Official Video)" has to land on
 * `trackName = "Flowers"`. So candidate titles and artists are normalised and scored: the title
 * has to agree (containment covers `Artist - Track (Official Video)` shapes) and, when the query
 * names an artist, the artist has to agree too — accounting for `VEVO` / `- Topic` channel names.
 * Anything else returns null and the thumbnail is used. A wrong cover is worse than a plain one.
 *
 * ### Caching
 * In-memory only. The durable copy is the library row's `artwork_url`, written when the track is
 * played; this cache just avoids repeating a lookup while a queue is shuffled back and forth.
 */
class ITunesAlbumArtSource(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://itunes.apple.com",
    private val cacheSize: Int = 256,
) : AlbumArtSource {

    private val json = Json { ignoreUnknownKeys = true }

    /** `null` values are cached too: a track with no match is not retried every play. */
    private val cache = object : LinkedHashMap<String, String?>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean =
            size > cacheSize
    }

    override suspend fun artworkFor(title: String, artist: String?): String? =
        withContext(Dispatchers.IO) {
            // "(Official Video)" and "VEVO" are upload metadata, not tags. Sending them to the
            // Search API made it return *zero* results ("SZA - Kill Bill (Official Video)
            // SZAVEVO"), so the query is cleaned first. The matching below still sees the
            // cleaned title, which is the part that names the song.
            val scanTitle = cleanTitle(title)
            val scanArtist = artist?.let(::cleanArtist)?.takeIf { it.isNotEmpty() }
            if (scanTitle.isEmpty()) return@withContext null

            val key = "${normalise(scanArtist.orEmpty())}|${normalise(scanTitle)}"
            synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }

            val found = try {
                search(scanTitle, scanArtist)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // A caller-side timeout is not a miss: caching null here would poison the entry
                // for the life of the process.
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.d(TAG, "lookup failed for \"$scanTitle\": ${error.message}")
                null
            }
            synchronized(cache) { cache[key] = found }
            android.util.Log.d(
                TAG,
                "artwork for \"$scanTitle\" / \"$scanArtist\": ${found ?: "no confident match"}",
            )
            found
        }

    private fun search(title: String, artist: String?): String? {
        val term = listOfNotNull(title, artist).joinToString(" ")
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("limit", "8")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.d(TAG, "iTunes answered ${response.code} for \"$term\"")
                return null
            }
            val body = response.body?.string().orEmpty()
            val results = json.decodeFromString<ITunesSearchResponse>(body).results
            bestMatch(results, title, artist)
                ?.artworkUrl100
                ?.let(::largerArtwork)
        }
    }

    /**
     * Removes bracketed upload annotations: `(Official Video)`, `[Lyrics]`, `(Audio)`, `(HD)`.
     *
     * The Search API treats every word as required, so one annotation can empty the result set.
     * The trimmed title is still a substring of a real `trackName`, which is what matching uses.
     */
    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Strips the channel suffixes that are not part of the artist's name. */
    private fun cleanArtist(raw: String): String = raw
        .replace(Regex("(?i)\\s*-\\s*topic$"), "")
        .replace(Regex("(?i)vevo$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Picks the most confident result, or null.
     *
     * Title containment catches the `Artist - Title (Official Video)` shape; an artist match
     * breaks ties between covers for different songs with the same name ("Intro", "Home").
     */
    private fun bestMatch(
        results: List<ITunesSong>,
        title: String,
        artist: String?,
    ): ITunesSong? {
        val wantedTitle = normalise(title)
        if (wantedTitle.isEmpty()) return null
        val wantedArtist = artist?.let(::normalise)?.takeIf { it.isNotEmpty() }

        val scored = results.asSequence()
            .filter { !it.artworkUrl100.isNullOrBlank() }
            .map { song ->
                Triple(
                    song,
                    titleScore(normalise(song.trackName.orEmpty()), wantedTitle),
                    artistScore(normalise(song.artistName.orEmpty()), wantedArtist),
                )
            }
            .filter { it.second > 0 }
            .toList()

        // With a known artist, a cover is used only when the candidate agrees on it: an exact
        // title is not enough when "Intro" is a different band's track. Channel suffixes
        // (`VEVO`, `- Topic`) are handled by containment, so real matches still come through.
        val pool = if (wantedArtist != null) scored.filter { it.third > 0 } else scored

        return pool.maxByOrNull { it.second + it.third }?.first
    }

    private fun titleScore(candidate: String, query: String): Int = when {
        candidate.isEmpty() -> 0
        candidate == query -> 3
        // The query is usually "Artist - Track (Official Video)". Requiring at least four
        // characters stops a one-word candidate like "I" from matching everything.
        candidate.length >= 4 && (query.contains(candidate) || candidate.contains(query)) -> 2
        else -> 0
    }

    private fun artistScore(candidate: String, wanted: String?): Int {
        if (wanted == null || candidate.isEmpty()) return 0
        return when {
            candidate == wanted -> 2
            candidate.contains(wanted) || wanted.contains(candidate) -> 1
            else -> 0
        }
    }

    /** `100x100bb.jpg` is the API's default; the same asset exists at 600px. */
    private fun largerArtwork(url: String): String =
        url.replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb.")

    /** Lowercase alphanumerics only, so punctuation and spacing cannot prevent a match. */
    private fun normalise(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private companion object {
        const val TAG = "AurisArt"
    }
}

@Serializable
private data class ITunesSearchResponse(
    val results: List<ITunesSong> = emptyList(),
)

@Serializable
private data class ITunesSong(
    val trackName: String? = null,
    val artistName: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
)
