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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Built-in connector for AllManga (https://allmanga.to).
 * Supports comprehensive manga library with multi-source chapter reading.
 */
class AllMangaSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val apiGateway: HttpUrl = "https://acapi.allanime.day/api".toHttpUrl(),
    private val site: HttpUrl = "https://allmanga.to".toHttpUrl(),
) : MediaSource, MangaReaderSource {
    override val id = ID
    override val name = "AllManga"
    override val mediaType = MediaType.MANGA
    override val baseUrl = site.toString().trimEnd('/')

    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()

    private val json = Json { ignoreUnknownKeys = true }

    private val JsonElement?.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject

    private val JsonElement?.jsonArrayOrNull: JsonArray?
        get() = this as? JsonArray

    override suspend fun popular(page: Int): SourcePage {
        require(page > 0)
        return queryMangas(page, query = "")
    }

    override suspend fun latest(page: Int): SourcePage {
        require(page > 0)
        return queryMangas(page, query = "")
    }

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0)
        return queryMangas(page, query = query.trim())
    }

    private suspend fun queryMangas(page: Int, query: String): SourcePage {
        val variables = buildJsonObject {
            putJsonObject("search") {
                put("query", query)
            }
            put("limit", PAGE_SIZE)
            put("page", page)
            put("translationType", "sub")
        }.toString()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", MANGA_LIST_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull ?: return SourcePage(emptyList(), false)
        val edges = root["data"].jsonObjectOrNull?.get("mangas").jsonObjectOrNull?.get("edges").jsonArrayOrNull
            ?: return SourcePage(emptyList(), false)

        val items = edges.mapNotNull { element ->
            val manga = element.jsonObjectOrNull ?: return@mapNotNull null
            val id = manga["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = manga["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumb = manga["thumbnail"]?.jsonPrimitive?.contentOrNull
            val year = manga["airedStart"].jsonObjectOrNull?.get("year")?.jsonPrimitive?.intOrNull
                ?: manga["season"].jsonObjectOrNull?.get("year")?.jsonPrimitive?.intOrNull
                ?: manga["year"]?.jsonPrimitive?.intOrNull
            SourceItem(
                url = id,
                title = title,
                thumbnailUrl = formatImageUrl(thumb),
                year = year,
            )
        }.distinctBy { it.url }

        val hasNext = items.size >= PAGE_SIZE
        return SourcePage(items, hasNext)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val manga = runCatching { fetchMangaDetails(entry.url) }.getOrNull() ?: return entry
        val title = manga["name"]?.jsonPrimitive?.contentOrNull ?: entry.title
        val thumb = formatImageUrl(manga["thumbnail"]?.jsonPrimitive?.contentOrNull) ?: entry.thumbnailUrl
        val desc = manga["description"]?.jsonPrimitive?.contentOrNull?.let {
            Jsoup.parseBodyFragment(it).text()
        }
        val author = when (val elem = manga["authors"]) {
            is JsonArray -> elem.mapNotNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    is JsonObject -> it["name"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.joinToString(", ").takeIf { it.isNotBlank() }
            is JsonPrimitive -> elem.contentOrNull
            else -> null
        }
        val chaptersDetail = manga["availableChaptersDetail"].jsonObjectOrNull
        val subCount = chaptersDetail?.get("sub").jsonArrayOrNull?.size ?: 0
        val rawCount = chaptersDetail?.get("raw").jsonArrayOrNull?.size ?: 0
        val epCount = subCount.takeIf { it > 0 } ?: rawCount.takeIf { it > 0 } ?: entry.episodeCount

        return entry.copy(
            title = title,
            thumbnailUrl = thumb,
            author = author ?: desc?.take(120) ?: entry.author,
            episodeCount = epCount,
        )
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val manga = fetchMangaDetails(entry.url)
        val chaptersDetail = manga["availableChaptersDetail"].jsonObjectOrNull
            ?: throw IOException("No chapters detail available for manga: ${entry.url}")

        val subList = chaptersDetail["sub"].jsonArrayOrNull?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val rawList = chaptersDetail["raw"].jsonArrayOrNull?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val chapterStrings = if (subList.isNotEmpty()) subList else rawList

        if (chapterStrings.isEmpty()) {
            return emptyList()
        }

        val sortedChapters = chapterStrings.distinct().sortedBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }

        return sortedChapters.mapIndexed { index, chapterStr ->
            val chapterNum = chapterStr.toIntOrNull() ?: (index + 1)
            SourceUnit(
                url = "${entry.url}<&sep>$chapterStr",
                index = chapterNum,
                title = "Chapter $chapterStr",
            )
        }
    }

    override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
        val parts = unit.url.split("<&sep>")
        val mangaId = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: entry.url
        val chapterString = parts.getOrNull(1) ?: unit.index.toString()

        val variables = buildJsonObject {
            put("mangaId", mangaId)
            put("translationType", "sub")
            put("chapterString", chapterString)
            put("limit", 250)
        }.toString()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", PAGES_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull
            ?: throw IOException("No chapter pages found for chapter $chapterString")
        val edges = root["data"].jsonObjectOrNull?.get("chapterPages").jsonObjectOrNull?.get("edges").jsonArrayOrNull
            ?: throw IOException("No chapter pages found for chapter $chapterString")
        if (edges.isEmpty()) {
            throw IOException("No chapter pages found for chapter $chapterString")
        }
        val pictureUrls = edges.firstOrNull()?.jsonObjectOrNull?.get("pictureUrls").jsonArrayOrNull
            ?: throw IOException("No pictureUrls found for chapter $chapterString")

        val pages = pictureUrls.mapIndexedNotNull { index, element ->
            val obj = element.jsonObjectOrNull ?: return@mapIndexedNotNull null
            val num = obj["num"]?.jsonPrimitive?.intOrNull ?: index
            val rawUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val imageUrl = formatImageUrl(rawUrl) ?: return@mapIndexedNotNull null
            Page(
                index = num,
                url = imageUrl,
                imageUrl = imageUrl,
                headers = mapOf(
                    "Referer" to REFERER,
                    "User-Agent" to USER_AGENT,
                ),
            )
        }

        return pages.sortedBy { it.index }
    }

    private suspend fun fetchMangaDetails(id: String): JsonObject {
        val query = "query(\$_id: String!) { manga(_id: \$_id) { _id name thumbnail description authors status availableChaptersDetail } }"
        val variables = buildJsonObject {
            put("_id", id)
        }
        val jsonStr = postApi(query, variables)
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull
            ?: throw IOException("AllManga details not found: $id")
        return root["data"].jsonObjectOrNull?.get("manga").jsonObjectOrNull
            ?: throw IOException("AllManga details not found: $id")
    }

    private fun formatImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return "$IMAGE_PREFIX${url.removePrefix("/")}"
    }

    private suspend fun queryApi(variables: String, extensions: String): String {
        val url = apiGateway.newBuilder()
            .addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("app-version", APP_VERSION)
            .header("from-app", FROM_APP)
            .header("platformstr", PLATFORM_STR)
            .header("Referer", REFERER)
            .header("User-Agent", USER_AGENT)
            .build()

        return executeRequest(request)
    }

    private suspend fun postApi(query: String, variables: JsonObject): String {
        val bodyJson = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()

        val request = Request.Builder()
            .url(apiGateway)
            .header("app-version", APP_VERSION)
            .header("from-app", FROM_APP)
            .header("platformstr", PLATFORM_STR)
            .header("Referer", REFERER)
            .header("User-Agent", USER_AGENT)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return executeRequest(request)
    }

    private suspend fun executeRequest(request: Request): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val resp = client.newCall(request).execute()
        resp.use {
            if (!it.isSuccessful) {
                throw IOException("AllManga API request failed: HTTP ${it.code}")
            }
            it.body?.string() ?: throw IOException("Empty response from AllManga API")
        }
    }

    companion object {
        const val ID = "allmanga"
        const val KEY = "builtin-allmanga"
        private const val PAGE_SIZE = 26
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val APP_VERSION = "android_c-253"
        private const val FROM_APP = "animechicken"
        private const val PLATFORM_STR = "android_c"
        private const val REFERER = "https://allmanga.to/"
        private const val IMAGE_PREFIX = "https://wp.youtube-anime.com/aln.youtube-anime.com/"
        private const val MANGA_LIST_HASH = "2d48e19fb67ddcac42fbb885204b6abb0a84f406f15ef83f36de4a66f49f651a"
        private const val PAGES_HASH = "a062f1b131dae3d17c1950fad14640d066b988ac10347ed49cfbe70f5e7f661b"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
