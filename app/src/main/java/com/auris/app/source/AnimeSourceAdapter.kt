package com.auris.app.source

import com.auris.app.core.model.MediaType
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.getChapterList
import eu.kanade.tachiyomi.animesource.getMangaDetails
import eu.kanade.tachiyomi.animesource.getPopularManga
import eu.kanade.tachiyomi.animesource.getSearchManga
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlin.math.ceil

/**
 * Adapts an extension source to Auris's [MediaSource] contract.
 *
 * ### One adapter, not two
 * Anikku collapses anime and manga into a single `AnimeSource` hierarchy (`SManga` is a
 * typealias for `SAnime`, `HttpSource` for `AnimeHttpSource`). So there is nothing to
 * dispatch on at runtime; [mediaType] records how Auris should *present* the source and which
 * manga-shaped methods to call. Two adapters would have been dead branches.
 *
 * ### Error behaviour
 * Extension calls throw whatever the extension throws (typically an `IOException`, or
 * `UnsupportedOperationException` for an endpoint it does not implement). That is
 * deliberately not translated: this adapter's job is shape translation, and swallowing
 * errors here would leave callers unable to tell "source failed" from "source returned
 * nothing".
 */
class AnimeSourceAdapter(
    // Named `aniyomiSource` rather than `source`: a constructor parameter shadows a property
    // of the same name inside property initialisers, so `source.baseUrl` below would resolve
    // against the `AnimeSource` parameter and fail to compile.
    aniyomiSource: AnimeSource,
    private val packageName: String,
    override val mediaType: MediaType = MediaType.ANIME,
) : MediaSource, MangaReaderSource {

    /**
     * Catalogue browsing needs an HTTP source.
     *
     * An extension may register any [AnimeSource], but every browse/detail/episode operation
     * here depends on `baseUrl` and the `*Request`/`*Parse` pair that only [AnimeHttpSource]
     * provides. Failing at construction with a clear message beats a `ClassCastException`
     * from deep inside a parse call.
     */
    private val http: AnimeHttpSource = requireNotNull(aniyomiSource as? AnimeHttpSource) {
        "$aniyomiSource is not an AnimeHttpSource; only HTTP sources can be adapted"
    }

    override val id: String = aniyomiSource.id.toString()
    override val name: String = aniyomiSource.name
    override val baseUrl: String = http.baseUrl

    // ---- Catalogue -----------------------------------------------------------

    override suspend fun popular(page: Int): SourcePage =
        http.getPopularAnime(page).toSourcePage(baseUrl)

    override suspend fun latest(page: Int): SourcePage =
        http.getLatestUpdates(page).toSourcePage(baseUrl)

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage =
        http.getSearchAnime(page, query, filters.toAnimeFilterList()).toSourcePage(baseUrl)

    /**
     * Manga-shaped details call.
     *
     * Under Anikku this is `getAnimeDetails` on the same object; the call is spelled the
     * manga way only when the source is being presented as manga, so the intent stays
     * visible at the call site.
     */
    override suspend fun details(entry: SourceItem): SourceItem {
        val anime = if (mediaType == MediaType.MANGA) {
            http.getMangaDetails(entry.toSAnime())
        } else {
            http.getAnimeDetails(entry.toSAnime())
        }

        return entry.copy(
            title = anime.title,
            // `originalThumbnailUrl` is Anikku's source-language artwork; the localised
            // `thumbnail_url` may be null when a source only sets the original.
            thumbnailUrl = resolveUrl(baseUrl, anime.thumbnail_url ?: anime.originalThumbnailUrl)
                ?: entry.thumbnailUrl,
            author = anime.author ?: anime.artist ?: entry.author,
        )
    }

    // ---- Units ---------------------------------------------------------------

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val anime = entry.toSAnime()
        val episodes = if (mediaType == MediaType.MANGA) {
            http.getChapterList(anime)
        } else {
            http.getEpisodeList(anime)
        }
        return episodes.map { it.toSourceUnit(baseUrl) }
    }

    /**
     * Playable targets. Empty for manga, which exposes page images via [pages] instead.
     *
     * Keeps "no streams" rather than faking one, so a caller bug stays obvious instead of
     * producing a player that cannot play a JPEG.
     */
    override suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget> {
        if (mediaType == MediaType.MANGA) return emptyList()

        return http.getVideoList(unit.toSEpisode())
            // Most sources return an embed page rather than a media URL; resolve first.
            .mapNotNull { video -> runCatching { http.resolveVideo(video) }.getOrNull() }
            .filterNotNull()
            .map { it.toStreamTarget() }
    }

    /**
     * Page images for a chapter, in reading order.
     *
     * ### How pages are obtained
     * Anikku collapses manga into the anime hierarchy, so there is no dedicated manga page API
     * to call. The image URLs come from the mechanism that *does* exist and that manga sources
     * populate: [AnimeHttpSource.getHosterList] then [AnimeHttpSource.getVideoList] per hoster.
     * A source offering several hosters contributes the pages of each, concatenated — which
     * matches how multi-hosting readers work, where the hosters are alternate mirrors of the
     * same chapter rather than different chapters.
     *
     * Sources that only implement [AnimeHttpSource.getVideoList] directly (no hoster list) are
     * handled by the single-hoster branch.
     *
     * Each URL is absolutised against the source's base: manga sites overwhelmingly return
     * relative `src` attributes, and handing `/1234/001.jpg` to Coil yields a broken image.
     *
     * ### Not verified against a live manga extension
     * This is the one part of the adapter built against inference rather than a verified
     * signature, because Anikku's extensions repository is DMCA-blocked and no real manga
     * extension was available to inspect. The behaviour is written to degrade to an empty list
     * rather than throw, so an unexpected shape surfaces as "this chapter has no pages" instead
     * of a crash.
     */
    override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
        val episode = unit.toSEpisode()

        val hosters = runCatching { http.getHosterList(episode) }
            .getOrElse { emptyList() }

        val videos = if (hosters.isNotEmpty()) {
            // One hoster failing must not lose the pages of the others, so each is isolated.
            hosters.flatMap { hoster ->
                runCatching { http.getVideoList(hoster) }.getOrDefault(emptyList())
            }
        } else {
            runCatching { http.getVideoList(episode) }.getOrDefault(emptyList())
        }

        return videos
            .mapNotNull { video ->
                // An embed page is not a readable image; keep only directly usable URLs.
                val absolute = resolveUrl(baseUrl, video.url) ?: return@mapNotNull null
                if (!absolute.startsWith("http")) return@mapNotNull null
                absolute
            }
            .distinct()
            .mapIndexed { index, url -> Page(index = index, url = url, imageUrl = url) }
    }

    override suspend fun configuration(): Map<String, String> = emptyMap()

    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
}

/** One image in a chapter. */
data class Page(val index: Int, val url: String, val imageUrl: String? = null)

// ---- Mapping ----------------------------------------------------------------

private fun eu.kanade.tachiyomi.animesource.model.AnimesPage.toSourcePage(baseUrl: String): SourcePage =
    SourcePage(
        items = animes.map { anime ->
            anime.toSourceItem().let { item ->
                item.copy(
                    thumbnailUrl = resolveUrl(
                        baseUrl,
                        item.thumbnailUrl ?: anime.originalThumbnailUrl,
                    ),
                )
            }
        },
        hasNextPage = hasNextPage,
    )

private fun SAnime.toSourceItem(): SourceItem = SourceItem(
    url = url,
    title = title,
    thumbnailUrl = thumbnail_url,
    author = author ?: artist,
    year = null, // Not part of Anikku's catalogue model; resolved via Kitsu instead.
)

/**
 * Aniyomi's `SourceItem.url` is source-relative. Rebuilding an [SAnime] therefore has to
 * restore the fields Anikku expects, including the `original*` twins — a source that reads
 * `originalTitle` during parsing must find it populated.
 */
private fun SourceItem.toSAnime(): SAnime = SAnimeImpl().apply {
    url = this@toSAnime.url
    title = this@toSAnime.title
    originalTitle = this@toSAnime.title
    thumbnail_url = this@toSAnime.thumbnailUrl
    originalThumbnailUrl = this@toSAnime.thumbnailUrl
    author = this@toSAnime.author
    originalAuthor = this@toSAnime.author
    // Localised twins default to the originals so a source reading either gets a value.
    syncOriginalsToLocalised()
}

private fun SEpisode.toSourceUnit(baseUrl: String): SourceUnit = SourceUnit(
    url = url,
    index = episodeNumberToIndex(episode_number),
    title = name,
    thumbnailUrl = resolveUrl(baseUrl, preview_url),
    uploadTimestamp = date_upload,
)

/**
 * Converts Anikku's float episode number to a 1-based index.
 *
 * Fractional numbers are used for premieres (12.5), so the mapping rounds up: episode 12.5
 * becomes unit 13, which is where a user would expect it.
 */
private fun episodeNumberToIndex(number: Float): Int =
    if (number < 0f) 0 else ceil(number).toInt()

private fun SourceUnit.toSEpisode(): SEpisode = SEpisodeImpl().apply {
    url = this@toSEpisode.url
    name = this@toSEpisode.title
    // `chapter_number` is the manga-spelled alias; set both so either reads correctly.
    episode_number = index.toFloat()
    chapter_number = index.toFloat()
    date_upload = uploadTimestamp
    scanlator = null
    summary = null
    preview_url = thumbnailUrl
    fillermark = false
}

private fun Video.toStreamTarget(): StreamTarget = StreamTarget(
    url = videoUrl,
    // OkHttp `Headers` is the ABI type; the host speaks a flat map. A source that supplies no
    // headers still yields an empty map rather than a null the player has to special-case.
    headers = headers?.toMultimap()?.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        ?: emptyMap(),
    mimeType = mimeType,
    // Anikku does not label DASH streams; ExoPlayer probes the manifest.
    isAdaptive = mimeType?.contains("dash", ignoreCase = true) ?: false,
)

/**
 * Resolves a possibly-relative URL against a base.
 *
 * Many extensions scrape artwork with `Element.attr("src")` rather than `absUrl("src")`, so
 * they return `/cover/x.jpg`. [com.auris.app.ui.components.MediaArtwork] hands the value
 * straight to Coil, which cannot resolve a relative path — absolutising here is what stops a
 * whole source showing blank artwork.
 */
internal fun resolveUrl(baseUrl: String, url: String?): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("http://", true) || url.startsWith("https://", true) -> url
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> baseUrl.trimEnd('/') + url
    else -> baseUrl.trimEnd('/') + "/" + url
}
