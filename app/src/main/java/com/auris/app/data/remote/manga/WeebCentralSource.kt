package com.auris.app.data.remote.manga

import com.auris.app.core.model.MediaType
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * High-quality English manga provider (successor to MangaSee / MangaLife).
 * Features official digital releases, volume rips, and scanlations.
 */
class WeebCentralSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val site: HttpUrl = "https://weebcentral.com/".toHttpUrl(),
) : MediaSource, MangaReaderSource {
    override val id = ID
    override val name = "Weeb Central"
    override val mediaType = MediaType.MANGA
    override val baseUrl = site.toString().trimEnd('/')

    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()

    override suspend fun popular(page: Int): SourcePage {
        require(page > 0) { "Page must be greater than 0" }
        val url = site.newBuilder()
            .addPathSegments("search/data")
            .addQueryParameter("sort", "Popularity")
            .addQueryParameter("order", "Descending")
            .addQueryParameter("display_mode", "Full Display")
            .addQueryParameter("limit", FETCH_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * FETCH_LIMIT).toString())
            .build()
        val doc = get(url)
        return parseCataloguePage(doc)
    }

    override suspend fun latest(page: Int): SourcePage {
        require(page > 0) { "Page must be greater than 0" }
        val url = site.newBuilder()
            .addPathSegments("search/data")
            .addQueryParameter("sort", "Latest Updates")
            .addQueryParameter("order", "Descending")
            .addQueryParameter("display_mode", "Full Display")
            .addQueryParameter("limit", FETCH_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * FETCH_LIMIT).toString())
            .build()
        val doc = get(url)
        return parseCataloguePage(doc)
    }

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0) { "Page must be greater than 0" }
        val cleanQuery = query.replace(EXCLUDED_SEARCH_CHARACTERS, " ").trim()
        val url = site.newBuilder()
            .addPathSegments("search/data")
            .addQueryParameter("text", cleanQuery)
            .addQueryParameter("display_mode", "Full Display")
            .addQueryParameter("limit", FETCH_LIMIT.toString())
            .addQueryParameter("offset", ((page - 1) * FETCH_LIMIT).toString())
            .build()
        val doc = get(url)
        return parseCataloguePage(doc)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val seriesUrl = site.resolve(entry.url.removePrefix("/")) ?: site.resolve(entry.url)
            ?: throw IOException("Invalid Weeb Central series path: ${entry.url}")
        val doc = get(seriesUrl)
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: entry.title
        val author = doc.select("ul > li:has(strong:contains(Author)) a").joinToString { it.text().trim() }
            .takeIf { it.isNotBlank() } ?: entry.author
        val thumb = doc.selectFirst("picture source")?.attr("srcset")?.replace("small", "normal")
            ?: doc.selectFirst("picture img")?.attr("src")
            ?: entry.thumbnailUrl
        return entry.copy(title = title, author = author, thumbnailUrl = thumb)
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val segments = pathSegments(entry.url)
        val seriesIndex = segments.indexOf("series")
        val seriesId = if (seriesIndex in 0 until segments.lastIndex) segments[seriesIndex + 1]
            else segments.firstOrNull() ?: throw IOException("Missing Weeb Central series identifier in ${entry.url}")
        val url = site.newBuilder()
            .addPathSegment("series")
            .addPathSegment(seriesId)
            .addPathSegment("full-chapter-list")
            .build()
        val doc = get(url)
        val rows = doc.select("div.flex.items-center:has(a[href*='/chapters/'])")
        if (rows.isEmpty()) {
            val fallbackRows = doc.select("a[href*='/chapters/']")
            return fallbackRows.mapNotNull { parseChapterLink(it) }
                .distinctBy { it.url }
                .reversed()
                .mapIndexed { index, unit -> unit.copy(index = index + 1) }
        }
        return rows.mapNotNull { row ->
            val link = row.selectFirst("a[href*='/chapters/']") ?: return@mapNotNull null
            val href = link.attr("href")
            val title = link.selectFirst("span.grow > span:first-child")?.text()?.trim()
                ?: link.text().trim()
            val uploadTime = row.selectFirst("time[datetime]")?.attr("datetime")
            val timestamp = parseIsoTimestamp(uploadTime)
            SourceUnit(
                url = href,
                index = 0,
                title = title.ifBlank { "Chapter" },
                uploadTimestamp = timestamp,
            )
        }.distinctBy { it.url }
            .reversed()
            .mapIndexed { index, unit -> unit.copy(index = index + 1) }
    }

    override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
        val chapterPath = unit.url.removePrefix("/")
        val url = site.resolve("$chapterPath/images?is_prev=False&reading_style=long_strip")
            ?: throw IOException("Invalid chapter URL: ${unit.url}")
        val doc = get(url)
        val images = doc.select("#chapter-images img[src], section[x-data~=scroll] img[src]")
        return images.mapNotNull { it.attr("abs:src").takeIf(String::isNotBlank) }
            .mapIndexed { index, imgUrl ->
                Page(
                    index = index,
                    url = imgUrl,
                    imageUrl = imgUrl,
                    headers = mapOf(
                        "Referer" to site.toString(),
                        "User-Agent" to USER_AGENT,
                    ),
                )
            }.ifEmpty {
                throw IOException("Weeb Central did not return chapter pages for ${unit.title}")
            }
    }

    private fun parseCataloguePage(doc: Document): SourcePage {
        val articles = doc.select("article:has(a[href*='/series/'])")
        val items = articles.mapNotNull { article ->
            val link = article.selectFirst("a.line-clamp-1")
                ?: article.selectFirst("a[href*='/series/']")
                ?: return@mapNotNull null
            val href = link.attr("href")
            val path = site.resolve(href)?.encodedPath ?: href
            val title = article.selectFirst("a.line-clamp-1")?.text()?.trim()
                ?: article.selectFirst("div.truncate")?.text()?.trim()
                ?: article.selectFirst("span.tooltip.tooltip-bottom:has(a[href*='/series/'])")?.attr("data-tip")
                ?: link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val thumb = article.selectFirst("picture source")?.attr("srcset")?.replace("small", "normal")
                ?: article.selectFirst("picture img")?.attr("abs:src")
            SourceItem(
                url = path,
                title = title,
                thumbnailUrl = thumb,
            )
        }.distinctBy { it.url }
        val hasNextPage = doc.selectFirst("button") != null || items.size >= FETCH_LIMIT
        return SourcePage(items, hasNextPage)
    }

    private fun parseChapterLink(link: Element): SourceUnit? {
        val href = link.attr("href").takeIf { it.contains("/chapters/") } ?: return null
        val title = link.text().trim()
        return SourceUnit(url = href, index = 0, title = title.ifBlank { "Chapter" })
    }

    private fun pathSegments(url: String): List<String> {
        val clean = url.substringBefore('?').substringBefore('#')
        return clean.split('/').filter(String::isNotBlank)
    }

    private fun parseIsoTimestamp(isoString: String?): Long {
        if (isoString.isNullOrBlank()) return 0L
        return runCatching {
            java.time.Instant.parse(isoString).toEpochMilli()
        }.getOrDefault(0L)
    }

    private suspend fun get(url: HttpUrl): Document = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", site.toString())
            .build()
        client.newCall(request).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) {
                throw IOException("Weeb Central request failed (HTTP ${response.code})")
            }
            val body = response.body?.string() ?: throw IOException("Empty Weeb Central response")
            Jsoup.parse(body, site.toString())
        }
    }

    companion object {
        const val ID = "weebcentral"
        const val KEY = "weebcentral"
        const val FETCH_LIMIT = 32
        private val EXCLUDED_SEARCH_CHARACTERS = "[!#:(),-]".toRegex()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}
