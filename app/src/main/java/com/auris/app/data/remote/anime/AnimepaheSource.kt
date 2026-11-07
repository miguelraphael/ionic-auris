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
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Built-in connector for Animepahe (https://animepahe.ru).
 * Delivers lightweight encodes and fast episode updates via Kwik and Pahe APIs.
 */
class AnimepaheSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .build(),
    private val site: HttpUrl = "https://animepahe.ru/".toHttpUrl(),
) : MediaSource {
    override val id = ID
    override val name = "Animepahe"
    override val mediaType = MediaType.ANIME
    override val baseUrl = site.toString().trimEnd('/')

    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun popular(page: Int): SourcePage = fetchAiring(page)

    override suspend fun latest(page: Int): SourcePage = fetchAiring(page)

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0)
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            return fetchAiring(page)
        }

        val url = site.newBuilder()
            .addPathSegment("api")
            .addQueryParameter("m", "search")
            .addQueryParameter("q", trimmed)
            .build()

        val jsonStr = get(url)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return SourcePage(emptyList(), false)

        val items = dataArray.mapNotNull { element ->
            val obj = element.jsonObject
            val session = obj["session"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val poster = obj["poster"]?.jsonPrimitive?.contentOrNull
            val year = obj["year"]?.jsonPrimitive?.intOrNull
            val epCount = obj["episodes"]?.jsonPrimitive?.intOrNull
            SourceItem(
                url = session,
                title = title,
                thumbnailUrl = poster,
                year = year,
                episodeCount = epCount,
            )
        }.distinctBy { it.url }

        return SourcePage(items, false)
    }

    private suspend fun fetchAiring(page: Int): SourcePage {
        require(page > 0)
        val url = site.newBuilder()
            .addPathSegment("api")
            .addQueryParameter("m", "airing")
            .addQueryParameter("page", page.toString())
            .build()

        val jsonStr = get(url)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return SourcePage(emptyList(), false)
        val lastPage = root["last_page"]?.jsonPrimitive?.intOrNull ?: 1

        val items = dataArray.mapNotNull { element ->
            val obj = element.jsonObject
            val session = obj["anime_session"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["anime_title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snapshot = obj["snapshot"]?.jsonPrimitive?.contentOrNull
                ?: obj["poster"]?.jsonPrimitive?.contentOrNull
            SourceItem(
                url = session,
                title = title,
                thumbnailUrl = snapshot,
            )
        }.distinctBy { it.url }

        val hasNext = page < lastPage
        return SourcePage(items, hasNext)
    }

    override suspend fun details(entry: SourceItem): SourceItem = entry

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val animeSession = entry.url
        val allUnits = mutableListOf<SourceUnit>()
        var currentPage = 1
        var lastPage = 1

        do {
            val url = site.newBuilder()
                .addPathSegment("api")
                .addQueryParameter("m", "release")
                .addQueryParameter("id", animeSession)
                .addQueryParameter("sort", "episode_asc")
                .addQueryParameter("page", currentPage.toString())
                .build()

            val jsonStr = get(url)
            val root = json.parseToJsonElement(jsonStr).jsonObject
            lastPage = root["last_page"]?.jsonPrimitive?.intOrNull ?: 1
            val dataArray = root["data"]?.jsonArray ?: break

            for (elem in dataArray) {
                val obj = elem.jsonObject
                val epSession = obj["session"]?.jsonPrimitive?.contentOrNull ?: continue
                val epNum = obj["episode"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: (allUnits.size + 1)
                val snapshot = obj["snapshot"]?.jsonPrimitive?.contentOrNull
                allUnits.add(
                    SourceUnit(
                        url = "$animeSession/$epSession",
                        index = epNum,
                        title = "Episode $epNum",
                        thumbnailUrl = snapshot,
                    )
                )
            }
            currentPage++
        } while (currentPage <= lastPage && currentPage <= 5)

        if (allUnits.isEmpty()) {
            throw IOException("Animepahe did not return any episodes for this series")
        }

        return allUnits
    }

    override suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget> {
        val parts = unit.url.split("/")
        val animeSession = parts.firstOrNull() ?: entry.url
        val epSession = parts.getOrNull(1) ?: return emptyList()

        val url = site.newBuilder()
            .addPathSegment("api")
            .addQueryParameter("m", "links")
            .addQueryParameter("id", animeSession)
            .addQueryParameter("session", epSession)
            .build()

        val jsonStr = get(url)
        val root = json.parseToJsonElement(jsonStr).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return emptyList()

        val targets = mutableListOf<StreamTarget>()
        for (elem in dataArray) {
            val obj = elem.jsonObject
            for ((key, value) in obj) {
                val streamObj = value.jsonObject
                val kwikUrl = streamObj["kwik"]?.jsonPrimitive?.contentOrNull ?: continue
                val audio = streamObj["audio"]?.jsonPrimitive?.contentOrNull ?: "sub"
                targets.add(
                    StreamTarget(
                        url = kwikUrl,
                        headers = mapOf(
                            "Referer" to site.toString(),
                            "User-Agent" to USER_AGENT,
                        ),
                        isAdaptive = kwikUrl.contains(".m3u8"),
                        label = "Animepahe · $key ($audio)",
                    )
                )
            }
        }

        return targets
    }

    private suspend fun get(url: HttpUrl): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", site.toString())
            .build()

        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            val resp = client.newCall(request).execute()
            resp.use {
                if (!it.isSuccessful) {
                    throw IOException("Animepahe request failed: HTTP ${it.code}")
                }
                it.body?.string() ?: throw IOException("Empty response from Animepahe")
            }
        }
    }

    companion object {
        const val ID = "animepahe"
        const val KEY = "builtin-animepahe"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
