package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Base class for sources whose media URLs must be resolved by scraping an embed page.
 *
 * The host calls [resolveVideo] before handing anything to ExoPlayer, so a [Video] with an
 * embed-page [Video.url] is safe to store: it never has to survive as a real media URL.
 *
 * ### Manga is anime
 * `eu.kanade.tachiyomi.source.online.ResolvableSource` is a typealias for this type, and
 * manga-shaped helpers ([getManga], [getChapter]) are extension functions here rather than
 * members — which is exactly why they cannot be overridden by extensions.
 */
abstract class ResolvableAnimeSource : AnimeHttpSource() {

    /** Resolve an embed page into a playable URL. */
    abstract override suspend fun resolveVideo(video: Video): Video?

    /** Look up a series by URL. Returns null when the URL is not recognised. */
    open suspend fun getAnime(uri: String): SManga? = null

    /** Look up an episode/chapter by URL. Returns null when the URL is not recognised. */
    open suspend fun getEpisode(uri: String): SChapter? = null
}

/** Manga-shaped aliases of [ResolvableAnimeSource.getAnime] / [getEpisode]. */
@Suppress("FunctionName", "UnusedReceiverParameter")
suspend fun ResolvableAnimeSource.getManga(uri: String): SManga? = getAnime(uri)

@Suppress("FunctionName", "UnusedReceiverParameter")
suspend fun ResolvableAnimeSource.getChapter(uri: String): SChapter? = getEpisode(uri)
