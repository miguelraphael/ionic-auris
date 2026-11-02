package com.auris.app.data.remote.manga

import com.auris.app.core.model.MediaType
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.coroutineContext

/** Built-in English manga source. Chapter images are resolved afresh for each reader open. */
class MangaDexSource(
    private val client: OkHttpClient = OkHttpClient(),
    private val apiUrl: HttpUrl = "https://api.mangadex.org/".toHttpUrl(),
    private val uploadsUrl: HttpUrl = "https://uploads.mangadex.org/".toHttpUrl(),
) : MediaSource, MangaReaderSource {
    override val id = ID
    override val name = "MangaDex"
    override val mediaType = MediaType.MANGA
    override val baseUrl = "https://mangadex.org"
    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()

    override suspend fun popular(page: Int) = catalogue(page, "followedCount")
    override suspend fun latest(page: Int) = catalogue(page, "latestUploadedChapter")
    override suspend fun search(page: Int, query: String, filters: FilterList) =
        catalogue(page, "relevance", query)

    private suspend fun catalogue(page: Int, order: String, query: String? = null): SourcePage {
        require(page >= 1)
        val offset = (page - 1) * PAGE_SIZE
        require(offset in 0 until 10_000) { "MangaDex catalogue limit reached. Narrow your search." }
        val url = apiUrl.newBuilder().addPathSegment("manga")
            .addQueryParameter("limit", minOf(PAGE_SIZE, 10_000 - offset).toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("availableTranslatedLanguage[]", "en")
            .addQueryParameter("hasAvailableChapters", "true")
            .addQueryParameter("contentRating[]", "safe")
            .addQueryParameter("contentRating[]", "suggestive")
            .addQueryParameter("order[$order]", "desc")
        if (!query.isNullOrBlank()) url.addQueryParameter("title", query.trim())
        val result = get(url.build())
        val data = result["data"]?.jsonArray ?: throw IOException("MangaDex returned no catalogue data")
        return SourcePage(data.map { manga(it.jsonObject) },
            offset + data.size < (result.int("total") ?: 0) && offset + data.size < 10_000)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val url = apiUrl.newBuilder().addPathSegment("manga").addPathSegment(uuid(entry.url))
            .addQueryParameter("includes[]", "cover_art").addQueryParameter("includes[]", "author").build()
        return manga(get(url)["data"]?.jsonObject ?: throw IOException("Manga not found"))
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val chapters = linkedMapOf<String, JsonObject>()
        var offset = 0
        do {
            coroutineContext.ensureActive()
            val url = apiUrl.newBuilder().addPathSegment("manga").addPathSegment(uuid(entry.url))
                .addPathSegment("feed")
                .addQueryParameter("translatedLanguage[]", "en")
                .addQueryParameter("includeExternalUrl", "0")
                .addQueryParameter("includeEmptyPages", "0")
                .addQueryParameter("includeFuturePublishAt", "0")
                .addQueryParameter("order[volume]", "asc")
                .addQueryParameter("order[chapter]", "asc")
                .addQueryParameter("limit", "500").addQueryParameter("offset", offset.toString()).build()
            val result = get(url)
            val data = result["data"]?.jsonArray ?: throw IOException("MangaDex returned no chapter data")
            for (element in data) {
                val chapter = element.jsonObject
                val attributes = chapter["attributes"]?.jsonObject ?: continue
                if ((attributes.int("pages") ?: 0) > 0 && attributes.string("externalUrl").isNullOrBlank()) {
                    chapters[chapter.string("id") ?: continue] = chapter
                }
            }
            offset += data.size
            val more = offset < (result.int("total") ?: 0) && data.isNotEmpty()
            // MangaDex limits feed pagination to 10,000; never silently return an incomplete list.
            if (more && offset >= 10_000) throw IOException("This title has too many chapters to load in one list")
        } while (more)
        // Chapter numbers may be fractional, repeated by different groups, or absent (oneshots).
        // Keep each chapter UUID and give it its own ordinal instead of rounding chapter numbers.
        return chapters.values.mapIndexed { index, chapter ->
            val attributes = chapter.getValue("attributes").jsonObject
            val title = listOfNotNull(
                attributes.string("volume")?.let { "Vol. $it" },
                attributes.string("chapter")?.let { "Ch. $it" },
                attributes.string("title")?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "Oneshot" }
            SourceUnit(chapter.getValue("id").jsonPrimitive.content, index + 1, title,
                uploadTimestamp = attributes.string("publishAt")?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
                } ?: 0L)
        }
    }

    override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
        val result = get(apiUrl.newBuilder().addPathSegment("at-home").addPathSegment("server")
            .addPathSegment(uuid(unit.url)).build())
        val server = result.string("baseUrl")?.toHttpUrl() ?: throw IOException("Missing chapter image server")
        if (!server.isHttps && apiUrl.isHttps) throw IOException("Insecure chapter image server")
        val chapter = result["chapter"]?.jsonObject ?: throw IOException("Missing chapter images")
        val hash = chapter.string("hash") ?: throw IOException("Missing chapter image hash")
        val files = chapter["data"]?.jsonArray ?: throw IOException("Missing chapter page list")
        return files.mapIndexed { index, file ->
            val url = server.newBuilder().addPathSegment("data").addPathSegment(hash)
                .addPathSegment(file.jsonPrimitive.content).build().toString()
            Page(index, url, url)
        }
    }

    private fun manga(resource: JsonObject): SourceItem {
        val id = resource.string("id") ?: throw IOException("Missing manga id")
        val attributes = resource["attributes"]?.jsonObject ?: throw IOException("Missing manga metadata")
        val titles = attributes["title"]?.jsonObject
        val title = titles?.string("en") ?: titles?.values?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "Untitled"
        val relations = resource["relationships"]?.jsonArray.orEmpty().map { it.jsonObject }
        val file = relations.firstOrNull { it.string("type") == "cover_art" }
            ?.get("attributes")?.jsonObject?.string("fileName")
        val artwork = file?.let { uploadsUrl.newBuilder().addPathSegment("covers").addPathSegment(id)
            .addPathSegment("$it.256.jpg").build().toString() }
        val author = relations.firstOrNull { it.string("type") == "author" }
            ?.get("attributes")?.jsonObject?.string("name")
        return SourceItem(id, title, artwork, author, year = attributes.int("year"))
    }

    private suspend fun get(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val request = Request.Builder().url(url).header("User-Agent", "Auris/0.1.0")
            .header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw IOException(when (response.code) {
                429 -> "MangaDex is busy. Please wait a moment before retrying."
                404 -> "This manga or chapter is no longer available on MangaDex."
                else -> "MangaDex request failed (HTTP ${response.code})"
            })
            val body = response.body?.string() ?: throw IOException("Empty MangaDex response")
            val result = Json.parseToJsonElement(body).jsonObject
            if (result.string("result") != "ok") throw IOException("MangaDex could not complete this request")
            result
        }
    }

    private fun uuid(value: String): String = java.util.UUID.fromString(value).toString()
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull

    companion object {
        const val ID = "mangadex"
        const val KEY = "builtin-mangadex"
        private const val PAGE_SIZE = 30
    }
}
