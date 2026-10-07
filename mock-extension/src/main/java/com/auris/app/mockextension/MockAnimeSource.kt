package com.auris.app.mockextension

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.VideoImpl
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A mock anime source, written against the **Anikku** API.
 *
 * It overrides exactly the surface a real Anikku extension overrides: the RxJava `fetch*`
 * catalogue methods plus the `*Request` / `*Parse(Response)` pairs, and the `original*`
 * fields on [SAnime]. Nothing Auris-specific appears below `eu.kanade.tachiyomi`, which is
 * what makes the test meaningful — if this loads, a real Anikku extension will too.
 *
 * The same class serves as a "manga" source: under Anikku, `SManga` is a typealias for
 * `SAnime`, so [MockMangaViewSource] is this source configured for chapter-shaped markup.
 */
open class MockAnimeSource : ParsedAnimeHttpSource() {

    override val name = "Mock Anime"
    override val lang = "en"
    override val baseUrl = "https://mock.auris.test"
    override val supportsLatest = true

    // ---- Request builders ----------------------------------------------------

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/popular?page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/latest?page=$page", headers)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api/search?q=$query&page=$page", headers)

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}/episodes", headers)

    override fun seasonListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}/seasons", headers)

    override fun hosterListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    // ---- Catalogue -----------------------------------------------------------

    override fun popularAnimeSelector(): String = "div.media"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnimeImpl().apply {
        url = element.selectFirst("a.link")?.attr("href") ?: ""
        title = element.selectFirst("div.title")?.text() ?: ""
        // Anikku idiom: the source-language values go in the `original*` twins.
        originalTitle = title
        originalThumbnailUrl = element.selectFirst("img.thumb")?.absUrl("src")
        syncOriginalsToLocalised()
    }

    override fun popularAnimeNextPageSelector(): String? = "a.next"

    override fun latestUpdatesSelector(): String = "div.media"

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnimeImpl().apply {
        url = element.selectFirst("a.link")?.attr("href") ?: ""
        title = element.selectFirst("div.title")?.text() ?: ""
        originalTitle = title
        syncOriginalsToLocalised()
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = "div.media"

    override fun searchAnimeFromElement(element: Element): SAnime = SAnimeImpl().apply {
        url = element.selectFirst("a.link")?.attr("href") ?: ""
        title = element.selectFirst("div.title")?.text() ?: ""
        originalTitle = title
        syncOriginalsToLocalised()
    }

    override fun searchAnimeNextPageSelector(): String? = null

    // ---- Details -------------------------------------------------------------

    override fun animeDetailsParse(document: Document): SAnime = SAnimeImpl().apply {
        url = document.selectFirst("a.back")?.attr("href") ?: "/anime/mock"
        originalTitle = document.selectFirst("h1.title")?.text() ?: ""
        originalAuthor = document.selectFirst("span.author")?.text()
        originalArtist = document.selectFirst("span.artist")?.text()
        originalDescription = document.selectFirst("div.description")?.text()
        originalGenre = document.select("li.genre").joinToString(", ") { it.text() }
        originalStatus = when (document.selectFirst("span.status")?.text()?.trim()) {
            "Airing" -> SAnime.ONGOING
            "Finished" -> SAnime.FINISHED
            else -> SAnime.UNKNOWN
        }
        originalThumbnailUrl = document.selectFirst("img.poster")?.absUrl("src")
        syncOriginalsToLocalised()
    }

    // ---- Episodes ------------------------------------------------------------

    override fun episodeListParse(document: Document): List<SEpisode> =
        document.select("div.episode").map { element ->
            SEpisodeImpl().apply {
                url = element.selectFirst("a.play")?.attr("href") ?: ""
                name = element.selectFirst("span.name")?.text() ?: ""
                episode_number = element.attr("data-number").toFloatOrNull() ?: -1f
                // Manga-shaped alias; a chapter source sets this instead.
                chapter_number = episode_number
                preview_url = element.selectFirst("img.thumb")?.absUrl("src")
                date_upload = 0L
            }
        }

    override fun seasonListParse(document: Document): List<SAnime> =
        document.select("div.season").map { element ->
            SAnimeImpl().apply {
                url = element.selectFirst("a.link")?.attr("href") ?: ""
                originalTitle = element.selectFirst("span.name")?.text() ?: ""
                syncOriginalsToLocalised()
            }
        }

    // ---- Hosters / video -----------------------------------------------------

    override fun hosterListParse(document: Document): List<Hoster> =
        document.select("a.hoster").map { element ->
            Hoster(
                name = element.attr("data-name"),
                hosterUrl = element.absUrl("href"),
            )
        }

    override fun videoListParse(document: Document): List<Video> =
        document.select("a.video").map { element ->
            VideoImpl().apply {
                url = element.attr("href")
                quality = element.attr("data-quality")
                mimeType = element.attr("data-mime").ifEmpty { null }
            }
        }

    override fun videoUrlParse(response: Response): String = response.use {
        it.body?.string()?.trim().orEmpty()
    }
}

/**
 * Second source in the same extension, distinguished only by language.
 *
 * Exercises the id derivation: same [name], different [lang] must produce different ids, and
 * a loader bug that keyed sources by name would be caught here.
 */
class MockAnimeAltSource : MockAnimeSource() {
    override val name = "Mock Anime"
    override val lang = "de"
    override val baseUrl = "https://mock-de.auris.test"
    override val supportsLatest = false
}

/**
 * A manga-shaped source.
 *
 * Under Anikku there is no separate manga hierarchy — this is an [AnimeSource] whose markup
 * happens to be chapter-shaped. It exists so the tests prove that manga-shaped extensions
 * load through the same path.
 */
class MockMangaViewSource : MockAnimeSource() {
    override val name = "Mock Manga"
    override val lang = "en"
    override val baseUrl = "https://mock-manga.auris.test"

    override fun episodeListParse(document: Document): List<SEpisode> =
        document.select("div.chapter").map { element ->
            SEpisodeImpl().apply {
                url = element.selectFirst("a.read")?.attr("href") ?: ""
                name = element.selectFirst("span.name")?.text() ?: ""
                chapter_number = element.attr("data-number").toFloatOrNull() ?: -1f
                episode_number = chapter_number
            }
        }

    override fun videoListParse(document: Document): List<Video> = emptyList()

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = emptyList()
}
