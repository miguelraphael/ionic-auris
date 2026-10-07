package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import java.io.Serializable

/**
 * An anime (or, by typealias, a manga) as exposed by a source.
 *
 * ### Shape
 * Mirrors `animesource/model/SAnime.kt` on Anikku `master`, which differs from Aniyomi's
 * original in one important way: every descriptive field has an `original*` twin.
 * Anikku keeps the source-language value in `originalTitle` / `originalAuthor` / etc. and
 * exposes the *localised* value as the plain `title` / `author`. Extensions written against
 * that API read `getOriginalTitle()`, so the properties are load-bearing, not cosmetic.
 *
 * `SManga` is a typealias for this type — see `source/model/SManga.kt`.
 */
@Suppress("PropertyName")
interface SAnime : Serializable {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var background_url: String?
    var update_strategy: AnimeUpdateStrategy
    var fetch_type: FetchType
    var season_number: Double
    var initialized: Boolean

    /** Source-language title, preserved before any localisation pass. */
    val originalTitle: String
    val originalAuthor: String?
    val originalArtist: String?
    val originalThumbnailUrl: String?
    val originalDescription: String?
    val originalGenre: String?
    val originalStatus: Int

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun create(): SAnime = SAnimeImpl()

    fun copy(): SAnime = create().also {
        it.url = url
        it.title = originalTitle
        it.artist = originalArtist
        it.author = originalAuthor
        it.description = originalDescription
        it.genre = originalGenre
        it.status = originalStatus
        it.thumbnail_url = originalThumbnailUrl
        it.background_url = background_url
        it.update_strategy = update_strategy
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val FINISHED = 2
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime = SAnimeImpl()
    }
}

/**
 * Default implementation.
 *
 * The `original*` properties are stored rather than computed: `title` is the localised
 * value, and deriving `originalTitle` from it would be lossy and would make the two collapse
 * as soon as localisation ran.
 */
class SAnimeImpl : SAnime {
    override lateinit var url: String
    override var title: String = ""

    @Transient
    override var originalTitle: String = ""

    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = SAnime.UNKNOWN
    override var thumbnail_url: String? = null
    override var background_url: String? = null
    override var update_strategy: AnimeUpdateStrategy = AnimeUpdateStrategy.ALWAYS_UPDATE
    override var fetch_type: FetchType = FetchType.EPISODE
    override var season_number: Double = -1.0
    override var initialized: Boolean = false

    override var originalAuthor: String? = null
    override var originalArtist: String? = null
    override var originalThumbnailUrl: String? = null
    override var originalDescription: String? = null
    override var originalGenre: String? = null
    override var originalStatus: Int = SAnime.UNKNOWN

    /**
     * Default the localised fields from the originals.
     *
     * An extension that sets only `originalTitle` (the Anikku idiom) still yields a usable
     * `title`, so a source written for either API shape displays correctly.
     */
    fun syncOriginalsToLocalised() {
        if (title.isBlank()) title = originalTitle
        if (author == null) author = originalAuthor
        if (artist == null) artist = originalArtist
        if (description == null) description = originalDescription
        if (genre == null) genre = originalGenre
        if (thumbnail_url == null) thumbnail_url = originalThumbnailUrl
        if (status == SAnime.UNKNOWN) status = originalStatus
    }

    override fun equals(other: Any?): Boolean =
        other is SAnime && other.url == url && other.title == title

    override fun hashCode(): Int = 31 * url.hashCode() + title.hashCode()

    override fun toString(): String = "SAnime(url=$url, title=$title)"
}

enum class AnimeUpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}

/**
 * Kept for binary compatibility.
 *
 * Anikku declares `SAnime.update_strategy` as `AnimeUpdateStrategy` but its `copy()`
 * overload takes an `UpdateStrategy`. Both enums exist; `updateStrategy()` below converts.
 */
fun SAnime.updateStrategy(): UpdateStrategy = when (update_strategy) {
    AnimeUpdateStrategy.ALWAYS_UPDATE -> UpdateStrategy.ALWAYS_UPDATE
    AnimeUpdateStrategy.ONLY_FETCH_ONCE -> UpdateStrategy.ONLY_FETCH_ONCE
}

/** Whether a URL refers to a series or a single episode. */
enum class FetchType {
    EPISODE,
    ANIME,
}

/** An anime related to another (sequel, spin-off, side story). */
data class AnimeRelation(
    val anime: SAnime,
    val relation: String,
)

/** Result of a combined details + episode refresh. */
data class AnimeEpisodeUpdate(
    val anime: SAnime,
    val episodes: List<SEpisode>,
    val hasNextPage: Boolean = false,
)