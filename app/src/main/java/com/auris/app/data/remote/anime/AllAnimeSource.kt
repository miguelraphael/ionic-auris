package com.auris.app.data.remote.anime

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
 * Built-in connector for AllAnime (https://allanime.to).
 * Supports vast library of subbed & dubbed anime with direct HLS (.m3u8) playback streams.
 */
class AllAnimeSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val apiGateway: HttpUrl = "https://acapi.allanime.day/api".toHttpUrl(),
    private val site: HttpUrl = "https://allanime.to".toHttpUrl(),
    private val clockGateway: HttpUrl = "https://allanime.day/apivtwo/clock.json".toHttpUrl(),
) : MediaSource {
    override val id = ID
    override val name = "AllAnime"
    override val mediaType = MediaType.ANIME
    override val baseUrl = site.toString().trimEnd('/')

    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun popular(page: Int): SourcePage {
        require(page > 0)
        val variables = buildJsonObject {
            put("type", "anime")
            put("size", PAGE_SIZE)
            put("page", page)
            put("dateRange", 7)
        }.toString()
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", POPULAR_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val recs = root["data"]?.jsonObject?.get("queryPopular")?.jsonObject?.get("recommendations")?.jsonArray
            ?: return SourcePage(emptyList(), false)

        val items = recs.mapNotNull { element ->
            val card = element.jsonObject["anyCard"]?.jsonObject ?: return@mapNotNull null
            val id = card["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = card["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumb = card["thumbnail"]?.jsonPrimitive?.contentOrNull
            val year = card["airedStart"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull
            SourceItem(
                url = id,
                title = title,
                thumbnailUrl = thumb,
                year = year,
            )
        }.distinctBy { it.url }

        val hasNext = items.size >= PAGE_SIZE
        return SourcePage(items, hasNext)
    }

    override suspend fun latest(page: Int): SourcePage {
        require(page > 0)
        return queryShows(page, null)
    }

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0)
        return queryShows(page, query.trim().takeIf { it.isNotBlank() })
    }

    private suspend fun queryShows(page: Int, searchKeyword: String?): SourcePage {
        val variables = buildJsonObject {
            putJsonObject("search") {
                if (searchKeyword != null) {
                    put("query", searchKeyword)
                }
                put("sortBy", "Latest_Update")
                put("allowAdult", false)
                put("allowUnknown", false)
                put("denyEcchi", false)
            }
            put("translationType", "sub")
            put("limit", PAGE_SIZE)
            put("page", page)
            put("countryOrigin", "ALL")
        }.toString()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", LATEST_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val edges = root["data"]?.jsonObject?.get("shows")?.jsonObject?.get("edges")?.jsonArray
            ?: return SourcePage(emptyList(), false)

        val items = edges.mapNotNull { element ->
            val show = element.jsonObject
            val id = show["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = show["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumb = show["thumbnail"]?.jsonPrimitive?.contentOrNull
            val year = show["season"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull
            SourceItem(
                url = id,
                title = title,
                thumbnailUrl = thumb,
                year = year,
            )
        }.distinctBy { it.url }

        val hasNext = items.size >= PAGE_SIZE
        return SourcePage(items, hasNext)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val variables = buildJsonObject {
            put("_id", entry.url)
        }.toString()
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", DETAILS_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val show = root["data"]?.jsonObject?.get("show")?.jsonObject ?: return entry

        val thumb = show["thumbnail"]?.jsonPrimitive?.contentOrNull ?: entry.thumbnailUrl
        val desc = show["description"]?.jsonPrimitive?.contentOrNull?.let {
            Jsoup.parseBodyFragment(it).text()
        }
        val author = show["studios"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val epCount = show["availableEpisodes"]?.jsonObject?.get("sub")?.jsonPrimitive?.intOrNull
            ?: show["availableEpisodes"]?.jsonObject?.get("dub")?.jsonPrimitive?.intOrNull
        val year = show["season"]?.jsonObject?.get("year")?.jsonPrimitive?.intOrNull ?: entry.year

        return entry.copy(
            thumbnailUrl = thumb,
            author = author ?: desc?.take(120),
            episodeCount = epCount,
            year = year,
        )
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val variables = buildJsonObject {
            put("_id", entry.url)
        }.toString()
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", DETAILS_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val show = root["data"]?.jsonObject?.get("show")?.jsonObject
            ?: throw IOException("AllAnime show not found: ${entry.url}")

        val epsDetail = show["availableEpisodesDetail"]?.jsonObject
        val subList = epsDetail?.get("sub")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val dubList = epsDetail?.get("dub")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val epStrings = if (subList.isNotEmpty()) subList else dubList

        if (epStrings.isEmpty()) {
            throw IOException("No episodes available for this show")
        }

        // Sort ascending numerically
        val sortedEps = epStrings.sortedBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }

        return sortedEps.mapIndexed { i, epStr ->
            val epNum = epStr.toIntOrNull() ?: (i + 1)
            SourceUnit(
                url = "${entry.url}<&sep>$epStr",
                index = epNum,
                title = "Episode $epStr",
            )
        }
    }

    override suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget> {
        val parts = unit.url.split("<&sep>")
        val showId = parts.firstOrNull() ?: entry.url
        val epStr = parts.getOrNull(1) ?: unit.index.toString()

        val variables = buildJsonObject {
            put("showId", showId)
            put("translationType", "sub")
            put("episodeString", epStr)
        }.toString()

        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", STREAMS_HASH)
            }
        }.toString()

        val jsonStr = queryApi(variables, extensions)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val sourceUrls = root["data"]?.jsonObject?.get("episode")?.jsonObject?.get("sourceUrls")?.jsonArray
            ?: return emptyList()

        val targets = mutableListOf<StreamTarget>()

        for (item in sourceUrls) {
            val obj = item.jsonObject
            val rawUrl = obj["sourceUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            val sourceName = obj["sourceName"]?.jsonPrimitive?.contentOrNull ?: "Server"
            val decryptedUrl = decryptUrl(rawUrl)

            if (decryptedUrl.startsWith("/apivtwo/clock")) {
                val clockId = decryptedUrl.substringAfter("id=", "")
                if (clockId.isNotBlank()) {
                    val streamLink = resolveClockStream(clockId)
                    if (streamLink != null) {
                        targets.add(
                            StreamTarget(
                                url = streamLink,
                                headers = mapOf(
                                    "Referer" to site.toString(),
                                    "User-Agent" to USER_AGENT,
                                ),
                                isAdaptive = streamLink.contains(".m3u8"),
                                label = "AllAnime · $sourceName (HLS)",
                            )
                        )
                    }
                }
            } else if (decryptedUrl.startsWith("http")) {
                targets.add(
                    StreamTarget(
                        url = decryptedUrl,
                        headers = mapOf(
                            "Referer" to site.toString(),
                            "User-Agent" to USER_AGENT,
                        ),
                        isAdaptive = decryptedUrl.contains(".m3u8"),
                        label = "AllAnime · $sourceName",
                    )
                )
            }
        }

        return targets
    }

    private suspend fun resolveClockStream(clockId: String): String? {
        val url = clockGateway.newBuilder().addQueryParameter("id", clockId).build()
        val request = Request.Builder()
            .url(url)
            .header("Referer", site.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val root = json.parseToJsonElement(body).jsonObject
                    val links = root["links"]?.jsonArray
                    links?.firstOrNull()?.jsonObject?.get("link")?.jsonPrimitive?.contentOrNull
                }
            }.getOrNull()
        }
    }

    private fun decryptUrl(url: String): String {
        if (!url.startsWith("--")) return url
        val hex = url.substringAfterLast("--")
        return buildString {
            for (i in hex.indices step 2) {
                if (i + 1 < hex.length) {
                    val byteVal = hex.substring(i, i + 2).toIntOrNull(16) ?: break
                    append((byteVal xor 56).toChar())
                }
            }
        }
    }

    private suspend fun queryApi(variables: String, extensions: String): String {
        val url = apiGateway.newBuilder()
            .addQueryParameter("variables", variables)
            .addQueryParameter("extensions", extensions)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("app-version", "android_c-253")
            .header("from-app", "animechicken")
            .header("platformstr", "android_c")
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val resp = client.newCall(request).execute()
            resp.use {
                if (!it.isSuccessful) {
                    throw IOException("AllAnime API request failed: HTTP ${it.code}")
                }
                it.body?.string() ?: throw IOException("Empty response from AllAnime API")
            }
        }
    }

    companion object {
        const val ID = "allanime"
        const val KEY = "builtin-allanime"
        private const val PAGE_SIZE = 26
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val POPULAR_HASH = "31a117653812a2547fd981632e8c99fa8bf8a75c4ef1a77a1567ef1741a7ab9c"
        private const val LATEST_HASH = "e42a4466d984b2c0a2cecae5dd13aa68867f634b16ee0f17b380047d14482406"
        private const val DETAILS_HASH = "bb263f91e5bdd048c1c978f324613aeccdfe2cbc694a419466a31edb58c0cc0b"
        private const val STREAMS_HASH = "5e7e17cdd0166af5a2d8f43133d9ce3ce9253d1fdb5160a0cfd515564f98d061"
    }
}
