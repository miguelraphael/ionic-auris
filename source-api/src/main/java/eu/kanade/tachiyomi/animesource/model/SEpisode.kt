package eu.kanade.tachiyomi.animesource.model

/**
 * An episode (or, by typealias, a chapter) as exposed by a source.
 *
 * ### Shape
 * Mirrors `animesource/model/SEpisode.kt` on Anikku `master`:
 *  - there is **no `video` field** — a source returns video separately via `getVideoList`;
 *  - `chapter_number` is a *property alias* over `episode_number`, not a stored field, so
 *    `setChapterNumber` must exist as a real method for manga-shaped sources;
 *  - `fillermark`, `summary` and `preview_url` are present.
 *
 * `SChapter` is a typealias for this type — see `source/model/SChapter.kt`.
 */
interface SEpisode {
    var url: String
    var name: String
    var date_upload: Long
    var episode_number: Float

    /** Marks a chapter the uploader had on file but could not verify. */
    var fillermark: Boolean
    var scanlator: String?
    var summary: String?
    var preview_url: String?

    fun create(): SEpisode = SEpisodeImpl()

    fun copyFrom(other: SEpisode) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        episode_number = other.episode_number
        fillermark = other.fillermark
        scanlator = other.scanlator
        summary = other.summary
        preview_url = other.preview_url
    }

    /**
     * Manga-shaped alias for [episode_number].
     *
     * A computed property in Anikku too — but a *real* getter/setter pair on the interface,
     * because `SChapter` sources call `chapter_number = x` and the JVM needs
     * `getChapterNumber`/`setChapterNumber` to exist on the interface.
     */
    var chapter_number: Float
        get() = episode_number
        set(value) {
            episode_number = value
        }

    companion object {
        fun create(): SEpisode = SEpisodeImpl()
    }
}

class SEpisodeImpl : SEpisode {
    override lateinit var url: String
    override var name: String = ""
    override var date_upload: Long = 0L
    override var episode_number: Float = -1f
    override var fillermark: Boolean = false
    override var scanlator: String? = null
    override var summary: String? = null
    override var preview_url: String? = null

    override fun equals(other: Any?): Boolean =
        other is SEpisode && other.url == url && other.episode_number == episode_number

    override fun hashCode(): Int = 31 * url.hashCode() + episode_number.hashCode()

    override fun toString(): String = "SEpisode(url=$url, number=$episode_number)"
}

/**
 * A playable stream for an episode.
 *
 * `url` is frequently a *page* rather than a media file; sources that need a resolver
 * implement [eu.kanade.tachiyomi.animesource.online.ResolvableAnimeSource] and the host
 * calls `resolveVideo` before playback.
 */
/**
 * A resolved, playable video.
 *
 * ### An `open class`, because an interface cannot be instantiated
 * This was an `interface`. Extensions **construct** videos — `Video(url, quality, videoUrl,
 * headers, subtitleTracks, audioTracks)` — and an interface has no constructor, so the call site
 * died with:
 *
 * ```
 * java.lang.InstantiationError: eu.kanade.tachiyomi.animesource.model.Video
 * ```
 *
 * The constructor above is read from a real extension's dex, along with the members it reads back
 * (`ffmpegStreamArgs`, `mpvArgs`, `videoTitle`). All parameters carry defaults so the host and the
 * mock extension can still write `Video()` and set properties afterwards; the full-arity
 * constructor is what Kotlin emits, and it is the descriptor extensions link against.
 *
 * `VideoImpl` below remains the host-side impl with mutable properties, for our own model.
 */
open class Video(
    url: String = "",
    quality: String = "",
    videoUrl: String = url,
    headers: okhttp3.Headers? = null,
    subtitleTracks: List<Any> = emptyList(),
    audioTracks: List<Any> = emptyList(),
) {
    open var url: String = url
    open var quality: String = quality

    /**
     * The direct media URL when it differs from [url].
     *
     * Computed rather than stored, because the real distinction is "embed page vs media file": a
     * source that only ever sets `url` (most do) must fall back to it, and a value captured from
     * a constructor argument goes stale the moment the host assigns `url` afterwards — which is
     * how the mock extension and every `Video()`-then-assign caller work.
     */
    open val videoUrl: String get() = videoUrlOverride.ifBlank { url }

    /** Set only by a source that genuinely distinguishes the embed page from the media file. */
    internal var videoUrlOverride: String = videoUrl

    open val headers: okhttp3.Headers? = headers
    open val subtitleTracks: List<Any> = subtitleTracks
    open val audioTracks: List<Any> = audioTracks

    /** Extra `ffmpeg` arguments a source may need to demux its stream. */
    open val ffmpegStreamArgs: List<String> = emptyList()

    /** Extra `mpv` arguments for sources served through the player's own demuxer. */
    open val mpvArgs: List<String> = emptyList()

    /** Human-readable title, used by the player when a source labels its own qualities. */
    open val videoTitle: String = url

    // ---- Host-side conveniences, not part of the extension ABI ----

    var mimeType: String? = null
    var subtitleUrl: String? = null
    var audioUrl: String? = null

    override fun toString(): String = "Video(url=$url, quality=$quality)"
}

/** The host-side implementation, kept as a convenient mutable factory for our own model. */
class VideoImpl : Video() {
    override var url: String = ""
    override var quality: String = ""
}

/** A host that serves videos for an episode; first entry is the preferred one. */
data class Hoster(
    val name: String,
    val hosterUrl: String,
    val videoListUrl: String? = null,
    val videoUrl: String? = null,
)

/** Scan tiles for seek previews. */
data class ThumbnailInfo(
    val images: List<String>,
    val imageWidth: Int,
    val imageHeight: Int = imageWidth,
    val thumbnailsPerRow: Int = 10,
)

/**
 * A page of catalogue results.
 *
 * `mangas` is an alias for `animes` (Anikku serves manga through the anime hierarchy), so
 * both spellings work.
 */
open class AnimesPage(
    open val animes: List<SAnime>,
    open val hasNextPage: Boolean,
) {
    val mangas: List<SAnime> get() = animes

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AnimesPage) return false
        if (animes != other.animes) return false
        return hasNextPage == other.hasNextPage
    }

    override fun hashCode(): Int = 31 * animes.hashCode() + hasNextPage.hashCode()

    fun copy(mangas: List<SAnime> = this.animes, hasNextPage: Boolean = this.hasNextPage) =
        AnimesPage(mangas, hasNextPage)

    override fun toString(): String = "AnimesPage(animes=$animes, hasNextPage=$hasNextPage)"
}