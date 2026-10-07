package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.ThumbnailInfo
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelperProvider
import eu.kanade.tachiyomi.network.awaitResult
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.awaitString
import eu.kanade.tachiyomi.util.awaitSingle
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import rx.Observable
import java.net.URI
import java.security.MessageDigest

/**
 * Base class for sources that make their requests over HTTP.
 *
 * ### Shape
 * Mirrors `animesource/online/AnimeHttpSource.kt` on Anikku `master`.
 *
 * The catalogue methods are RxJava-based here, exactly as upstream:
 *
 * ```kotlin
 * override fun fetchPopularAnime(page: Int): Observable<AnimesPage> = ...
 * suspend fun getPopularAnime(page: Int): AnimesPage = fetchPopularAnime(page).awaitSingle()
 * ```
 *
 * Extensions override the `fetch*` and `*Request`/`*Parse` members, so the suspend-facing
 * methods here must keep those defaults intact — a host that "modernised" them to suspend-only
 * would stop loading every real extension.
 */
abstract class AnimeHttpSource : eu.kanade.tachiyomi.animesource.AnimeCatalogueSource {

    /** Site root, no trailing slash. */
    abstract val baseUrl: String

    /**
     * Bumped when a site changes in a way that invalidates stored URLs. It feeds [id], so
     * changing it yields a new source instead of reusing incompatible state.
     */
    open val versionId: Int = 1

    override val id: Long by lazy { generateId(name, lang, versionId) }

    /** Headers applied to every request. Override [headersBuilder] to customise. */
    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = NetworkHelperProvider.get().client

    override fun toString(): String = "$name (${lang.uppercase()})"

    /**
     * Derives the source id exactly as Aniyomi does.
     *
     * Must match byte for byte, otherwise the same source installed in both apps gets two
     * ids and its library entries will not line up. Algorithm: MD5 of
     * `"${name.lowercase()}/$lang/$versionId"`, first 8 bytes read big-endian, sign bit
     * cleared.
     */
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "$name/$lang/$versionId".lowercase()
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7)
            .map { bytes[it].toLong() and 0xffL shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", NetworkHelperProvider.get().defaultUserAgentProvider())
    }

    open fun getHomeUrl(): String = baseUrl

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ---- Popular -------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        client.newCall(popularAnimeRequest(page))
            .asObservableSuccess()
            .map { popularAnimeParse(it) }

    /** Extensions override this to point at the right path. */
    @Suppress("DEPRECATION")
    protected open fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    @Suppress("DEPRECATION")
    protected open fun popularAnimeParse(response: okhttp3.Response): AnimesPage =
        throw UnsupportedOperationException("popularAnimeParse")

    override suspend fun getPopularAnime(page: Int): AnimesPage =
        fetchPopularAnime(page).awaitSingle()

    // ---- Latest --------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { latestUpdatesParse(it) }

    @Suppress("DEPRECATION")
    protected open fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    @Suppress("DEPRECATION")
    protected open fun latestUpdatesParse(response: okhttp3.Response): AnimesPage =
        throw UnsupportedOperationException("latestUpdatesParse")

    override suspend fun getLatestUpdates(page: Int): AnimesPage =
        fetchLatestUpdates(page).awaitSingle()

    // ---- Search --------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> =
        client.newCall(searchAnimeRequest(page, query, filters))
            .asObservableSuccess()
            .map { searchAnimeParse(it) }

    @Suppress("DEPRECATION")
    protected open fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = GET(baseUrl, headers)

    @Suppress("DEPRECATION")
    protected open fun searchAnimeParse(response: okhttp3.Response): AnimesPage =
        throw UnsupportedOperationException("searchAnimeParse")

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = fetchSearchAnime(page, query, filters).awaitSingle()

    // ---- Details -------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        client.newCall(animeDetailsRequest(anime))
            .asObservableSuccess()
            .map { animeDetailsParse(it).apply { initialized = true } }

    open fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    @Suppress("DEPRECATION")
    protected open fun animeDetailsParse(response: okhttp3.Response): SAnime =
        throw UnsupportedOperationException("animeDetailsParse")

    @Suppress("DEPRECATION")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime =
        fetchAnimeDetails(anime).awaitSingle()

    // ---- Episodes ------------------------------------------------------------

    @Suppress("DEPRECATION")
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        client.newCall(episodeListRequest(anime))
            .asObservableSuccess()
            .map { episodeListParse(it) }

    @Suppress("DEPRECATION")
    protected open fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    @Suppress("DEPRECATION")
    protected open fun episodeListParse(response: okhttp3.Response): List<SEpisode> =
        throw UnsupportedOperationException("episodeListParse")

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> =
        fetchEpisodeList(anime).awaitSingle()

    // ---- Seasons -------------------------------------------------------------

    /**
     * Abstract upstream. The default here returns a single season so a source that predates
     * the API still loads, but any source that declares its own wins.
     */
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> =
        seasonListRequest(anime).awaitResult { seasonListParse(it) }

    @Suppress("DEPRECATION")
    protected open fun seasonListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    @Suppress("DEPRECATION")
    protected open fun seasonListParse(response: okhttp3.Response): List<SAnime> =
        throw UnsupportedOperationException("seasonListParse")

    // ---- Hosters / video -----------------------------------------------------

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> =
        hosterListRequest(episode).awaitResult { hosterListParse(it) }

    @Suppress("DEPRECATION")
    protected open fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    @Suppress("DEPRECATION")
    protected open fun hosterListParse(response: okhttp3.Response): List<Hoster> =
        throw UnsupportedOperationException("hosterListParse")

    override suspend fun getVideoList(hoster: Hoster): List<Video> =
        videoListRequest(hoster).awaitResult { videoListParse(it, hoster) }

    @Suppress("DEPRECATION")
    protected open fun videoListRequest(hoster: Hoster): Request = GET(hoster.hosterUrl, headers)

    @Suppress("DEPRECATION")
    protected open fun videoListParse(response: okhttp3.Response, hoster: Hoster): List<Video> =
        throw UnsupportedOperationException("videoListParse")

    /** Resolves a [Video] whose [Video.url] is an embed page rather than a media URL. */
    open suspend fun resolveVideo(video: Video): Video? = video

    @Suppress("DEPRECATION")
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        client.newCall(videoListRequest(episode))
            .asObservableSuccess()
            .map { videoListParse(it) }

    @Suppress("DEPRECATION")
    protected open fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    @Suppress("DEPRECATION")
    protected open fun videoListParse(response: okhttp3.Response): List<Video> =
        throw UnsupportedOperationException("videoListParse")

    @Suppress("DEPRECATION")
    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        fetchVideoList(episode).awaitSingle()

    @Suppress("DEPRECATION")
    open suspend fun getVideoUrl(video: Video): String =
        videoUrlRequest(video).awaitResult { videoUrlParse(it) }

    @Suppress("DEPRECATION")
    protected open fun videoUrlRequest(video: Video): Request = GET(video.url, headers)

    @Suppress("DEPRECATION")
    protected open fun videoUrlParse(response: okhttp3.Response): String =
        throw UnsupportedOperationException("videoUrlParse")

    /** Scan tiles for seek previews; `null` when the source has none. */
    open suspend fun getVideoThumbnails(video: Video): ThumbnailInfo? = null

    // ---- Misc ----------------------------------------------------------------

    open fun getAnimeUrl(anime: SAnime): String = animeDetailsRequest(anime).url.toString()

    open fun getEpisodeUrl(episode: SEpisode): String = episode.url

    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) = Unit

    /** Strips scheme and host so stored URLs survive a domain change. */
    fun getUrlWithoutDomain(url: String): String = try {
        val uri = URI(url)
        buildString {
            append(uri.path ?: "")
            uri.query?.let { append('?').append(it) }
            uri.fragment?.let { append('#').append(it) }
        }
    } catch (_: Exception) {
        url
    }

    @Suppress("Unused")
    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    @Suppress("Unused")
    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    open fun List<Hoster>.sortHosters(): List<Hoster> = this

    open fun List<Video>.sortVideos(): List<Video> = this
}