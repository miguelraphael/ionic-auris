package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import rx.Observable

/**
 * Creates the sources provided by an extension, one instance per configuration.
 *
 * The extension loader reflects on this interface to discover sources inside an APK.
 */
interface AnimeSourceFactory {
    fun createSources(): List<AnimeSource>
}

/**
 * An anime source.
 *
 * ### Shape
 * Mirrors `animesource/AnimeSource.kt` on Anikku `master`.
 *
 * Two deliberate differences from Aniyomi's original, both load-bearing:
 *  - **`getAnimeEpisodeUpdate` does not exist.** Anikku replaced it with the simpler
 *    `getSeasonList`. A host that declares it abstract cannot load any Anikku extension.
 *  - **The RxJava `fetch*` methods are part of the contract**, and
 *    `AnimeCatalogueSource.getPopularAnime` is expressed as `fetchPopularAnime(...).awaitSingle()`.
 *    Extensions override the `fetch*` forms, so the host must ship RxJava 1.x and provide
 *    `awaitSingle`.
 */
interface AnimeSource {
    val id: Long
    val name: String

    val lang: String
        get() = ""

    suspend fun getAnimeDetails(anime: SAnime): SAnime

    suspend fun getEpisodeList(anime: SAnime): List<SEpisode>

    /** Seasons of an anime. Abstract in Anikku — extensions must implement it. */
    suspend fun getSeasonList(anime: SAnime): List<SAnime>

    suspend fun getHosterList(episode: SEpisode): List<eu.kanade.tachiyomi.animesource.model.Hoster> =
        throw IllegalStateException("Not used")

    suspend fun getVideoList(hoster: eu.kanade.tachiyomi.animesource.model.Hoster): List<Video> =
        throw IllegalStateException("Not used")

    suspend fun getVideoList(episode: SEpisode): List<Video>

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getAnimeDetails"))
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        throw IllegalStateException("Not used")

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getEpisodeList"))
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        throw IllegalStateException("Not used")

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getVideoList"))
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        throw IllegalStateException("Not used")
}

/** An [AnimeSource] that exposes a browsable catalogue. */
interface AnimeCatalogueSource : AnimeSource {
    override val lang: String
    val supportsLatest: Boolean

    suspend fun getPopularAnime(page: Int): AnimesPage

    suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage

    suspend fun getLatestUpdates(page: Int): AnimesPage

    fun getFilterList(): AnimeFilterList

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularAnime"))
    fun fetchPopularAnime(page: Int): Observable<AnimesPage>

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchAnime"))
    fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage>

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
    fun fetchLatestUpdates(page: Int): Observable<AnimesPage>

    val supportsRelatedAnimes: Boolean get() = false
    val supportsRelatedMangas: Boolean get() = supportsRelatedAnimes
}

// ---- Manga-shaped conveniences -------------------------------------------------
//
// Anikku keeps these as extension functions on the anime interface; a manga source is
// simply an anime source asked the manga questions.

suspend fun AnimeCatalogueSource.getPopularManga(page: Int): MangasPage = getPopularAnime(page)

suspend fun AnimeCatalogueSource.getSearchManga(
    page: Int,
    query: String,
    filters: FilterList,
): MangasPage = getSearchAnime(page, query, filters)

@Suppress("UNUSED_PARAMETER")
suspend fun AnimeSource.getMangaDetails(manga: SAnime): SAnime = getAnimeDetails(manga)

@Suppress("UNUSED_PARAMETER")
suspend fun AnimeSource.getChapterList(manga: SAnime): List<SEpisode> = getEpisodeList(manga)