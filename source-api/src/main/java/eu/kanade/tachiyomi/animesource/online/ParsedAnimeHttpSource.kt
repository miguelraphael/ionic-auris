package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkHelperProvider
import eu.kanade.tachiyomi.network.awaitResult
import eu.kanade.tachiyomi.network.awaitString
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Convenience base class for sources that scrape with CSS selectors.
 *
 * Mirrors `animesource/online/ParsedAnimeHttpSource.kt` on Anikku `master`. Extensions
 * override the `*Parse(Response)` members rather than the Rx ones, so the abstract surface
 * here is `*Request` + `*Parse` per endpoint.
 *
 * Every method is overridable, so a source with unusual markup can bypass the helpers.
 */
abstract class ParsedAnimeHttpSource : AnimeHttpSource() {

    /**
     * The host's shared HTTP client, exposed to subclasses.
     *
     * ### Why this exists
     * Real extensions call `network` from their own request builders and interceptors. Aniyomi
     * declares it on `ParsedHttpSource`, so the expected descriptor is
     * `getNetwork()Leu/kanade/tachiyomi/network/NetworkHelper;` on this class.
     *
     * Its absence was invisible until a real extension was loaded, and then it presented as a
     * `NoSuchMethodError` naming a class we do provide — which reads like the extension being
     * broken rather than the host being incomplete.
     *
     * `protected` matches Aniyomi: it is for subclasses, not the host UI.
     */
    protected open val network: NetworkHelper get() = NetworkHelperProvider.get()

    // ---- Popular -------------------------------------------------------------

    protected abstract fun popularAnimeSelector(): String

    protected abstract fun popularAnimeFromElement(element: Element): SAnime

    protected abstract fun popularAnimeNextPageSelector(): String?

    @Suppress("DEPRECATION")
    override fun popularAnimeParse(response: Response): AnimesPage = parseWith(
        response.asJsoup(),
        popularAnimeSelector(),
        ::popularAnimeFromElement,
        popularAnimeNextPageSelector(),
    )

    // ---- Latest --------------------------------------------------------------

    protected abstract fun latestUpdatesSelector(): String

    protected abstract fun latestUpdatesFromElement(element: Element): SAnime

    protected abstract fun latestUpdatesNextPageSelector(): String?

    @Suppress("DEPRECATION")
    override fun latestUpdatesParse(response: Response): AnimesPage = parseWith(
        response.asJsoup(),
        latestUpdatesSelector(),
        ::latestUpdatesFromElement,
        latestUpdatesNextPageSelector(),
    )

    // ---- Search --------------------------------------------------------------

    protected abstract fun searchAnimeSelector(): String

    protected abstract fun searchAnimeFromElement(element: Element): SAnime

    protected abstract fun searchAnimeNextPageSelector(): String?

    @Suppress("DEPRECATION")
    override fun searchAnimeParse(response: Response): AnimesPage = parseWith(
        response.asJsoup(),
        searchAnimeSelector(),
        ::searchAnimeFromElement,
        searchAnimeNextPageSelector(),
    )

    // ---- Details -------------------------------------------------------------

    protected abstract fun animeDetailsParse(document: Document): SAnime

    @Suppress("DEPRECATION")
    override fun animeDetailsParse(response: Response): SAnime =
        animeDetailsParse(response.asJsoup())

    // ---- Episodes ------------------------------------------------------------

    protected abstract fun episodeListParse(document: Document): List<SEpisode>

    @Suppress("DEPRECATION")
    override fun episodeListParse(response: Response): List<SEpisode> =
        episodeListParse(response.asJsoup())

    // ---- Seasons -------------------------------------------------------------

    protected open fun seasonListParse(document: Document): List<SAnime> = emptyList()

    @Suppress("DEPRECATION")
    override fun seasonListParse(response: Response): List<SAnime> =
        seasonListParse(response.asJsoup())

    // ---- Hosters -------------------------------------------------------------

    protected open fun hosterListParse(document: Document): List<eu.kanade.tachiyomi.animesource.model.Hoster> =
        emptyList()

    @Suppress("DEPRECATION")
    override fun hosterListParse(response: Response): List<eu.kanade.tachiyomi.animesource.model.Hoster> =
        hosterListParse(response.asJsoup())

    // ---- Video ---------------------------------------------------------------

    protected abstract fun videoListParse(document: Document): List<Video>

    @Suppress("DEPRECATION")
    override fun videoListParse(response: Response): List<Video> =
        videoListParse(response.asJsoup())

    @Suppress("DEPRECATION")
    override fun videoListParse(response: Response, hoster: eu.kanade.tachiyomi.animesource.model.Hoster): List<Video> =
        videoListParse(response.asJsoup())

    @Suppress("DEPRECATION")
    override fun videoUrlParse(response: Response): String = response.use {
        it.body?.string().orEmpty()
    }

    // ---- Shared helper -------------------------------------------------------

    protected fun parseWith(
        document: Document,
        selector: String,
        mapper: (Element) -> SAnime,
        nextPageSelector: String?,
    ): AnimesPage {
        val animes = document.select(selector).map(mapper)
        val hasNextPage = nextPageSelector?.let { document.select(it).first() != null } ?: false
        return AnimesPage(animes, hasNextPage)
    }

    @Suppress("unused")
    protected fun html(document: String): Document = Jsoup.parse(document, baseUrl)

    @Suppress("unused")
    protected suspend fun fetchHtml(request: okhttp3.Request): String = request.awaitString()

    @Suppress("unused")
    protected suspend fun <T> awaitBody(request: okhttp3.Request, block: (Response) -> T): T =
        request.awaitResult(block)
}
