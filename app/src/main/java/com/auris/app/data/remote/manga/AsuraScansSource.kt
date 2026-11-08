package com.auris.app.data.remote.manga

import com.auris.app.core.model.MediaType
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Built-in English manhwa & comics provider for Asura Scans.
 * Uses the Asura Scans REST API for catalogue and chapter listing,
 * and parses chapter web pages for image URLs.
 */
class AsuraScansSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val site: HttpUrl = "https://asurascans.com/".toHttpUrl(),
    private val apiSite: HttpUrl = "https://api.asurascans.com/".toHttpUrl(),
) : MediaSource, MangaReaderSource {

    override val id = ID
    override val name = "Asura Scans"
    override val mediaType = MediaType.MANGA
    override val baseUrl = site.toString().trimEnd('/')

    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()

    override suspend fun popular(page: Int): SourcePage {
        require(page > 0) { "Page must be greater than 0" }
        return fetchSeriesPage(page)
    }

    override suspend fun latest(page: Int): SourcePage {
        require(page > 0) { "Page must be greater than 0" }
        return fetchSeriesPage(page)
    }

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0) { "Page must be greater than 0" }
        if (page > 1) return SourcePage(emptyList(), false)

        val url = apiSite.newBuilder()
            .addPathSegment("api")
            .addPathSegment("search")
            .addQueryParameter("q", query.trim())
            .build()

        val json = getJson(url)
        val data = json["data"]?.jsonArray
            ?: throw IOException("Asura Scans returned no search results")

        val items = data.mapNotNull { it as? JsonObject }.map(::parseSeriesItem)
        return SourcePage(items, false)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val slug = extractSlug(entry.url)
        if (slug.isBlank()) throw IOException("Invalid Asura Scans series identity: ${entry.url}")

        val url = apiSite.newBuilder()
            .addPathSegment("api")
            .addPathSegment("series")
            .addPathSegment(slug)
            .build()

        val json = getJson(url)
        val series = json["data"]?.jsonObject ?: json

        val title = series.string("title")?.takeIf { it.isNotBlank() } ?: entry.title
        val cover = series.string("cover")
            ?: series.string("thumbnail")
            ?: series.string("thumbnail_url")
            ?: entry.thumbnailUrl
        val author = series.string("author") ?: entry.author
        val count = series.int("total_chapters")
            ?: series.int("chapter_count")
            ?: series.int("chapters_count")
            ?: entry.episodeCount
        val year = series.int("year") ?: series.int("release_year") ?: entry.year

        return entry.copy(
            title = title,
            thumbnailUrl = cover,
            author = author,
            episodeCount = count,
            year = year,
        )
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val slug = extractSlug(entry.url)
        if (slug.isBlank()) throw IOException("Invalid Asura Scans series identity: ${entry.url}")

        val url = apiSite.newBuilder()
            .addPathSegment("api")
            .addPathSegment("series")
            .addPathSegment(slug)
            .addPathSegment("chapters")
            .build()

        val json = getJson(url)
        val data = json["data"]?.jsonArray
            ?: throw IOException("Asura Scans returned no chapters")

        val unitsWithSortKey = data.mapNotNull { it as? JsonObject }.map { chapter ->
            val seriesSlug = chapter.string("series_slug")?.takeIf { it.isNotBlank() } ?: slug
            val numPrimitive = chapter["number"]?.jsonPrimitive
            val numStr = numPrimitive?.content?.trim() ?: "0"
            val numDouble = numPrimitive?.doubleOrNull ?: numStr.toDoubleOrNull() ?: 0.0
            val numInt = numPrimitive?.intOrNull ?: numStr.toIntOrNull() ?: numDouble.toInt()

            val rawTitle = chapter.string("title")?.trim()
            val title = if (!rawTitle.isNullOrBlank()) rawTitle else "Chapter $numStr"
            val chapterUrl = "/comics/$seriesSlug/chapter/$numStr"
            val uploadTimestamp = chapter.string("created_at")?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            } ?: 0L

            numDouble to SourceUnit(
                url = chapterUrl,
                index = numInt,
                title = title,
                uploadTimestamp = uploadTimestamp,
            )
        }

        // Ascending reading order (Chapter 1 first)
        return unitsWithSortKey.sortedBy { it.first }.map { it.second }
    }

    override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
        val chapterUrl = if (unit.url.startsWith("http://") || unit.url.startsWith("https://")) {
            unit.url.toHttpUrl()
        } else {
            site.resolve(unit.url)
                ?: (baseUrl + if (unit.url.startsWith("/")) unit.url else "/${unit.url}").toHttpUrl()
        }

        val doc = getHtml(chapterUrl)
        val images = doc.select("img[data-page-index], img[src*=/asura-images/chapters/]")

        val sortedImages = images.sortedBy { img ->
            img.attr("data-page-index").toIntOrNull() ?: Int.MAX_VALUE
        }

        val referer = if (site.host.contains("asurascans.com")) REFERER else site.toString()

        val pages = sortedImages.mapNotNull { img ->
            val src = img.attr("src").takeIf(String::isNotBlank)
                ?: img.attr("data-src").takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val resolvedUrl = chapterUrl.resolve(src)?.toString()
                ?: site.resolve(src)?.toString()
                ?: src
            resolvedUrl
        }.distinct().mapIndexed { index, url ->
            Page(
                index = index,
                url = url,
                imageUrl = url,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to USER_AGENT,
                ),
            )
        }

        if (pages.isEmpty()) {
            throw IOException("Asura Scans did not return readable chapter images. Please retry.")
        }
        return pages
    }

    private suspend fun fetchSeriesPage(page: Int): SourcePage {
        val url = apiSite.newBuilder()
            .addPathSegment("api")
            .addPathSegment("series")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
            .build()

        val json = getJson(url)
        val data = json["data"]?.jsonArray
            ?: throw IOException("Asura Scans returned no series data")

        val items = data.mapNotNull { it as? JsonObject }.map(::parseSeriesItem)
        val meta = json["meta"]?.jsonObject
        val hasMore = meta?.get("has_more")?.jsonPrimitive?.booleanOrNull
            ?: meta?.get("hasNext")?.jsonPrimitive?.booleanOrNull
            ?: (items.size >= 20)

        return SourcePage(items, hasMore)
    }

    private fun parseSeriesItem(item: JsonObject): SourceItem {
        val title = item.string("title") ?: "Untitled"
        val slug = item.string("slug")
        val publicUrl = item.string("public_url")
        val url = formatSeriesUrl(publicUrl, slug)
        val cover = item.string("cover")
            ?: item.string("thumbnail")
            ?: item.string("thumbnail_url")
        val author = item.string("author")
        return SourceItem(
            url = url,
            title = title,
            thumbnailUrl = cover,
            author = author,
        )
    }

    private fun formatSeriesUrl(publicUrl: String?, slug: String?): String {
        if (!publicUrl.isNullOrBlank()) {
            val path = if (publicUrl.startsWith("http://") || publicUrl.startsWith("https://")) {
                runCatching { publicUrl.toHttpUrl().encodedPath }.getOrDefault(publicUrl)
            } else {
                publicUrl
            }
            val clean = path.trim().trim('/')
            return if (clean.startsWith("comics/")) "/$clean" else "/comics/$clean"
        }
        if (!slug.isNullOrBlank()) {
            val clean = slug.trim().trim('/')
            return if (clean.startsWith("comics/")) "/$clean" else "/comics/$clean"
        }
        return ""
    }

    private fun extractSlug(url: String): String {
        val path = if (url.startsWith("http://") || url.startsWith("https://")) {
            runCatching { url.toHttpUrl().encodedPath }.getOrDefault(url)
        } else {
            url
        }
        return path.trim().trim('/').removePrefix("comics/").removePrefix("comics").trim('/')
    }

    private suspend fun getJson(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", site.toString())
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        429 -> "Asura Scans is busy. Please wait a moment before retrying."
                        404 -> "Item not found on Asura Scans (HTTP 404)"
                        else -> "Asura Scans request failed (HTTP ${response.code})"
                    }
                )
            }
            val body = response.body?.string() ?: throw IOException("Empty Asura Scans response")
            val element = Json.parseToJsonElement(body)
            element as? JsonObject ?: throw IOException("Unexpected JSON response from Asura Scans")
        }
    }

    private suspend fun getHtml(url: HttpUrl): org.jsoup.nodes.Document = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", site.toString())
            .build()

        client.newCall(request).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) {
                throw IOException("Asura Scans request failed (HTTP ${response.code})")
            }
            val body = response.body?.string() ?: throw IOException("Empty Asura Scans response")
            Jsoup.parse(body, url.toString())
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

    companion object {
        const val ID = "asurascans"
        const val REFERER = "https://asurascans.com/"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
