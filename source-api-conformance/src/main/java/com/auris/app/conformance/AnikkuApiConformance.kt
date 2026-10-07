package com.auris.app.conformance

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import rx.Observable

/**
 * Compile-time conformance assertions for `:source-api`.
 *
 * Every declaration here mirrors a signature an Anikku-compiled extension links against.
 * **This file is not executed.** It exists so that the Kotlin compiler rejects any change to
 * the vendored layer that would break extension loading:
 *
 *  - removing a member -> unresolved reference
 *  - renaming a member -> unresolved reference
 *  - changing a signature -> type mismatch
 *  - making a member non-abstract / adding an abstract member -> override errors
 *
 * ### Baseline
 * Anikku `master`, `komikku-app/anikku`. Sources under
 * `source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/`.
 *
 * @see <a href="https://github.com/komikku-app/anikku">Anikku</a>
 */
@Suppress("unused", "UNUSED_PARAMETER")
object AnikkuApiConformance {

    // ---- Identity collapse ----------------------------------------------------
    //
    // The single most important structural fact: manga *is* anime. If any of these stop
    // being typealiases, the host has diverged from Anikku and will misclassify every
    // extension.

    fun mangaIsAnime(manga: SManga): SAnime = manga
    fun animeIsManga(anime: SAnime): SManga = anime

    fun chapterIsEpisode(chapter: SChapter): SEpisode = chapter
    fun episodeIsChapter(episode: SEpisode): SChapter = episode

    fun httpSourceIsAnimeHttpSource(source: HttpSource): AnimeHttpSource = source
    fun animeHttpSourceIsHttpSource(source: AnimeHttpSource): HttpSource = source

    fun parsedHttpSourceIsParsed(source: ParsedHttpSource): ParsedAnimeHttpSource = source

    fun catalogueSourceIsAnimeSource(source: CatalogueSource): eu.kanade.tachiyomi.animesource.AnimeCatalogueSource = source

    // ---- Catalogue surface ----------------------------------------------------
    //
    // `fetchPopularAnime` and friends are RxJava 1 and *abstract*: every extension overrides
    // them. Removing RxJava from the surface would break extension loading outright.

    fun fetchPopular(source: AnimeHttpSource, page: Int): Observable<*> =
        source.fetchPopularAnime(page)

    fun fetchSearch(
        source: AnimeHttpSource,
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.animesource.model.AnimeFilterList,
    ): Observable<*> = source.fetchSearchAnime(page, query, filters)

    fun fetchLatest(source: AnimeHttpSource, page: Int): Observable<*> =
        source.fetchLatestUpdates(page)

    suspend fun suspendCatalogue(source: AnimeHttpSource, page: Int) {
        source.getPopularAnime(page)
        source.getLatestUpdates(page)
        source.getSearchAnime(page, "q", eu.kanade.tachiyomi.animesource.model.AnimeFilterList())
        source.getFilterList()
    }

    // ---- Detail / episode surface --------------------------------------------
    //
    // `getAnimeEpisodeUpdate` was REMOVED in Anikku in favour of `getSeasonList`, and
    // `getSeasonList` is abstract. Declaring `getAnimeEpisodeUpdate` again would make every
    // real extension fail with AbstractMethodError at first use.

    suspend fun details(source: AnimeHttpSource, anime: SAnime) = source.getAnimeDetails(anime)
    suspend fun episodes(source: AnimeHttpSource, anime: SAnime) = source.getEpisodeList(anime)
    suspend fun seasons(source: AnimeHttpSource, anime: SAnime) = source.getSeasonList(anime)

    fun rxDetails(source: AnimeHttpSource, anime: SAnime): Observable<*> =
        source.fetchAnimeDetails(anime)

    fun rxEpisodes(source: AnimeHttpSource, anime: SAnime): Observable<*> =
        source.fetchEpisodeList(anime)

    fun rxVideos(source: AnimeHttpSource, episode: SEpisode): Observable<*> =
        source.fetchVideoList(episode)

    // ---- Video ---------------------------------------------------------------

    suspend fun hosters(source: AnimeHttpSource, episode: SEpisode) = source.getHosterList(episode)
    suspend fun videosForHoster(source: AnimeHttpSource, hoster: eu.kanade.tachiyomi.animesource.model.Hoster) =
        source.getVideoList(hoster)

    suspend fun videos(source: AnimeHttpSource, episode: SEpisode) = source.getVideoList(episode)
    suspend fun resolve(source: AnimeHttpSource, video: Video) = source.resolveVideo(video)
    suspend fun videoUrl(source: AnimeHttpSource, video: Video) = source.getVideoUrl(video)

    // ---- SAnime original* twins ----------------------------------------------
    //
    // Anikku keeps source-language values in `original*`; extensions read and write them.

    fun originalTitle(anime: SAnime): String = anime.originalTitle
    fun originalAuthor(anime: SAnime): String? = anime.originalAuthor
    fun originalArtist(anime: SAnime): String? = anime.originalArtist
    fun originalThumbnail(anime: SAnime): String? = anime.originalThumbnailUrl
    fun originalDescription(anime: SAnime): String? = anime.originalDescription
    fun originalGenre(anime: SAnime): String? = anime.originalGenre
    fun originalStatus(anime: SAnime): Int = anime.originalStatus

    fun genres(anime: SAnime): List<String>? = anime.getGenres()
    fun copyAnime(anime: SAnime): SAnime = anime.copy()

    // ---- SEpisode / SChapter -------------------------------------------------

    /**
     * `chapter_number` is a *property alias* over `episode_number`. A manga source that
     * assigns it needs the real setter to exist.
     */
    fun readChapterNumber(chapter: SChapter): Float = chapter.chapter_number
    fun writeChapterNumber(chapter: SChapter, value: Float) {
        chapter.chapter_number = value
    }

    fun episodeFields(episode: SEpisode) {
        episode.url
        episode.name
        episode.date_upload
        episode.episode_number
        episode.fillermark
        episode.scanlator
        episode.summary
        episode.preview_url
    }

    fun copyFrom(episode: SEpisode, other: SEpisode) = episode.copyFrom(other)

    // ---- Factories -----------------------------------------------------------

    fun createSources(factory: eu.kanade.tachiyomi.animesource.AnimeSourceFactory) =
        factory.createSources()

    fun createMangaSources(factory: SourceFactory) = factory.createSources()

    // ---- Request / parse hooks extensions override ---------------------------

    fun requestHooks(source: AnimeHttpSource, anime: SAnime) {
        source.baseUrl
        source.name
        source.lang
        source.versionId
        source.headers
        source.client
        source.getHomeUrl()
        source.getAnimeUrl(anime)
        source.getUrlWithoutDomain("https://x/y")
        source.toString()
    }

    fun identity(source: AnimeHttpSource): Long = source.id

    /** `getSeasonList` is abstract upstream; a conformant host must not force extra members. */
    suspend fun episodeListIsAbstract(source: AnimeHttpSource, anime: SAnime) = source.getEpisodeList(anime)

    /** `AnimesPage.mangas` aliases `animes`; both spellings must resolve. */
    fun pageBothSpellings(page: eu.kanade.tachiyomi.animesource.model.AnimesPage): Int =
        page.animes.size + page.mangas.size

    fun pageCopy(page: eu.kanade.tachiyomi.animesource.model.AnimesPage) = page.copy()
}