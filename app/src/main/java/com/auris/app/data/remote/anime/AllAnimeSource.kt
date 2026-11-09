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

    private val JsonElement?.jsonObjectOrNull: JsonObject?
        get() = this as? JsonObject

    private val JsonElement?.jsonArrayOrNull: JsonArray?
        get() = this as? JsonArray

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
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull ?: return SourcePage(emptyList(), false)
        val recs = root["data"].jsonObjectOrNull?.get("queryPopular").jsonObjectOrNull?.get("recommendations").jsonArrayOrNull
            ?: return SourcePage(emptyList(), false)

        val items = recs.mapNotNull { element ->
            val card = element.jsonObjectOrNull?.get("anyCard").jsonObjectOrNull ?: return@mapNotNull null
            val id = card["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = card["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumb = card["thumbnail"]?.jsonPrimitive?.contentOrNull
            val year = card["airedStart"].jsonObjectOrNull?.get("year")?.jsonPrimitive?.intOrNull
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
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull ?: return SourcePage(emptyList(), false)
        val edges = root["data"].jsonObjectOrNull?.get("shows").jsonObjectOrNull?.get("edges").jsonArrayOrNull
            ?: return SourcePage(emptyList(), false)

        val items = edges.mapNotNull { element ->
            val show = element.jsonObjectOrNull ?: return@mapNotNull null
            val id = show["_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = show["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val thumb = show["thumbnail"]?.jsonPrimitive?.contentOrNull
            val year = show["season"].jsonObjectOrNull?.get("year")?.jsonPrimitive?.intOrNull
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
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull ?: return entry
        val show = root["data"].jsonObjectOrNull?.get("show").jsonObjectOrNull ?: return entry

        val thumb = show["thumbnail"]?.jsonPrimitive?.contentOrNull ?: entry.thumbnailUrl
        val desc = show["description"]?.jsonPrimitive?.contentOrNull?.let {
            Jsoup.parseBodyFragment(it).text()
        }
        val author = show["studios"].jsonArrayOrNull?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val epCount = show["availableEpisodes"].jsonObjectOrNull?.get("sub")?.jsonPrimitive?.intOrNull
            ?: show["availableEpisodes"].jsonObjectOrNull?.get("dub")?.jsonPrimitive?.intOrNull
        val year = show["season"].jsonObjectOrNull?.get("year")?.jsonPrimitive?.intOrNull ?: entry.year

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
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull
            ?: throw IOException("AllAnime show not found: ${entry.url}")
        val show = root["data"].jsonObjectOrNull?.get("show").jsonObjectOrNull
            ?: throw IOException("AllAnime show not found: ${entry.url}")

        val epsDetail = show["availableEpisodesDetail"].jsonObjectOrNull
        val subList = epsDetail?.get("sub").jsonArrayOrNull?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val dubList = epsDetail?.get("dub").jsonArrayOrNull?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val epStrings = if (subList.isNotEmpty()) subList else dubList

        if (epStrings.isEmpty()) {
            throw IOException("No episodes available for this show")
        }

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
        val root = json.parseToJsonElement(jsonStr).jsonObjectOrNull ?: return emptyList()
        val sourceUrls = root["data"].jsonObjectOrNull?.get("episode").jsonObjectOrNull?.get("sourceUrls").jsonArrayOrNull
            ?: return emptyList()

        val targets = mutableListOf<StreamTarget>()

        for (item in sourceUrls) {
            val obj = item.jsonObjectOrNull ?: continue
            val rawUrl = obj["sourceUrl"]?.jsonPrimitive?.contentOrNull ?: continue
            val sourceName = obj["sourceName"]?.jsonPrimitive?.contentOrNull ?: "Server"
            val decryptedUrl = decryptUrl(rawUrl)

            if (decryptedUrl.contains("/apivtwo/clock") || decryptedUrl.contains("/apiv2/clock")) {
                val clockId = decryptedUrl.substringAfter("id=", "").substringBefore("&")
                if (clockId.isNotBlank()) {
                    val clockStreams = resolveClockStreams(clockId, sourceName)
                    targets.addAll(clockStreams)
                }
            } else if (decryptedUrl.contains("mp4upload.com")) {
                resolveMp4Upload(decryptedUrl)?.let { targets.add(it) }
            } else if (decryptedUrl.contains("streamwish") || decryptedUrl.contains("filemoon") || decryptedUrl.contains("bysekoze")) {
                resolvePackedStream(decryptedUrl, sourceName)?.let { targets.add(it) }
            } else if (decryptedUrl.startsWith("http")) {
                val isDirectStream = decryptedUrl.contains(".m3u8") || decryptedUrl.contains(".mp4")
                val isEmbedPage = decryptedUrl.contains("/embed") || decryptedUrl.contains("/e/") || decryptedUrl.endsWith(".html")
                if (isDirectStream && !isEmbedPage) {
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
        }

        return targets
    }

    private suspend fun resolveClockStreams(clockId: String, sourceName: String): List<StreamTarget> {
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
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string() ?: return@use emptyList()
                    val root = json.parseToJsonElement(body).jsonObjectOrNull ?: return@use emptyList()
                    val links = root["links"].jsonArrayOrNull ?: return@use emptyList()
                    val list = mutableListOf<StreamTarget>()
                    for (item in links) {
                        val linkObj = item.jsonObjectOrNull ?: continue
                        val streamUrl = linkObj["link"]?.jsonPrimitive?.contentOrNull ?: continue
                        val isHls = linkObj["hls"]?.jsonPrimitive?.booleanOrNull ?: streamUrl.contains(".m3u8")
                        val res = linkObj["resolutionStr"]?.jsonPrimitive?.contentOrNull
                        val label = if (res != null) {
                            "AllAnime · $sourceName ($res)"
                        } else if (isHls) {
                            "AllAnime · $sourceName (HLS)"
                        } else {
                            "AllAnime · $sourceName"
                        }
                        val headers = mutableMapOf<String, String>()
                        val rawHeaders = linkObj["headers"].jsonObjectOrNull
                        if (rawHeaders != null) {
                            for ((k, v) in rawHeaders) {
                                v.jsonPrimitive.contentOrNull?.let { headers[k] = it }
                            }
                        }
                        if (!headers.containsKey("Referer")) {
                            headers["Referer"] = site.toString()
                        }
                        headers["User-Agent"] = USER_AGENT

                        list.add(
                            StreamTarget(
                                url = streamUrl,
                                headers = headers,
                                isAdaptive = isHls,
                                label = label,
                            )
                        )
                    }
                    list
                }
            }.getOrDefault(emptyList())
        }
    }

    private suspend fun resolveMp4Upload(embedUrl: String): StreamTarget? {
        val request = Request.Builder()
            .url(embedUrl)
            .header("Referer", site.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string() ?: return@use null
                    val mp4Match = Regex("""player\.src\(\s*(?:\{\s*type:[^,]+,\s*src:\s*)?["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)
                        ?: Regex("""src:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)
                    val directUrl = mp4Match?.groupValues?.get(1) ?: return@use null
                    StreamTarget(
                        url = directUrl,
                        headers = mapOf(
                            "Referer" to "https://mp4upload.com/",
                            "User-Agent" to USER_AGENT,
                        ),
                        isAdaptive = false,
                        label = "AllAnime · Mp4Upload (Direct MP4)",
                    )
                }
            }.getOrNull()
        }
    }

    private suspend fun resolvePackedStream(embedUrl: String, sourceName: String): StreamTarget? {
        val request = Request.Builder()
            .url(embedUrl)
            .header("Referer", site.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val html = resp.body?.string() ?: return@use null
                    val unpacked = JsUnpacker.unpack(html)
                    val m3u8Match = Regex("""(?:sources|file|source)\s*:\s*(?:\[\s*\{\s*file:\s*)?["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)
                        ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)
                    val directUrl = m3u8Match?.groupValues?.get(1) ?: return@use null
                    StreamTarget(
                        url = directUrl,
                        headers = mapOf(
                            "Referer" to embedUrl,
                            "User-Agent" to USER_AGENT,
                        ),
                        isAdaptive = true,
                        label = "AllAnime · $sourceName (HLS)",
                    )
                }
            }.getOrNull()
        }
    }

    fun decryptUrl(url: String): String {
        val match = prefixRegex.find(url) ?: return url
        val (prefix, hexData) = match.destructured
        val mask = maskMap[prefix] ?: 56

        return buildString {
            for (i in hexData.indices step 2) {
                if (i + 1 < hexData.length) {
                    val byteVal = hexData.substring(i, i + 2).toIntOrNull(16) ?: break
                    append((byteVal xor mask).toChar())
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

        private val keyMap = mapOf(
            "--" to "s5feqxw21",
            "#-" to "1234567890123456789012345",
            "##" to "1234567890123456789",
            "-#" to "feqx1",
            "#" to "allanimenews"
        )

        private val maskMap = keyMap.mapValues { (_, key) ->
            key.fold(0) { acc, char -> acc xor char.code }
        }

        private val prefixRegex = Regex("""^(--|#-|-#|##|#)([0-9a-fA-F]+)$""")

        private const val POPULAR_HASH = "31a117653812a2547fd981632e8c99fa8bf8a75c4ef1a77a1567ef1741a7ab9c"
        private const val LATEST_HASH = "e42a4466d984b2c0a2cecae5dd13aa68867f634b16ee0f17b380047d14482406"
        private const val DETAILS_HASH = "bb263f91e5bdd048c1c978f324613aeccdfe2cbc694a419466a31edb58c0cc0b"
        private const val STREAMS_HASH = "5e7e17cdd0166af5a2d8f43133d9ce3ce9253d1fdb5160a0cfd515564f98d061"
    }
}
