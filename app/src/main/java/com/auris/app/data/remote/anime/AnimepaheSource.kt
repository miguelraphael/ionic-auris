package com.auris.app.data.remote.anime

import com.auris.app.core.model.MediaType
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.FormBody
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
        .followRedirects(true)
        .followSslRedirects(true)
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

        val jsonStr = runCatching { get(url) }.getOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: return emptyList()
        val dataArray = root["data"]?.jsonArray ?: return emptyList()

        return coroutineScope {
            val deferredTargets = dataArray.flatMap { elem ->
                val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@flatMap emptyList()
                obj.map { (key, value) ->
                    async {
                        runCatching {
                            val streamObj = value.jsonObject
                            val kwikUrl = streamObj["kwik"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                            val audio = streamObj["audio"]?.jsonPrimitive?.contentOrNull ?: "sub"
                            val label = "Animepahe · $key ($audio)"
                            resolveKwik(kwikUrl, label)
                        }.getOrNull()
                    }
                }
            }
            deferredTargets.awaitAll().filterNotNull()
        }
    }

    private suspend fun resolveKwik(kwikUrl: String, label: String): StreamTarget? {
        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            runCatching {
                val kwikCandidates = mutableListOf(kwikUrl)
                val parsedUri = runCatching { kwikUrl.toHttpUrl() }.getOrNull()
                if (parsedUri != null && parsedUri.host in KWIK_DOMAINS) {
                    for (domain in KWIK_DOMAINS) {
                        if (domain != parsedUri.host) {
                            kwikCandidates.add(parsedUri.newBuilder().host(domain).build().toString())
                        }
                    }
                }

                for (candidateUrl in kwikCandidates) {
                    coroutineContext.ensureActive()
                    val target = attemptResolveKwik(candidateUrl, label)
                    if (target != null) return@runCatching target
                }
                null
            }.getOrNull()
        }
    }

    private fun attemptResolveKwik(kwikUrl: String, label: String): StreamTarget? {
        val request = Request.Builder()
            .url(kwikUrl)
            .header("Referer", site.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        val resp = runCatching { client.newCall(request).execute() }.getOrNull() ?: return null
        resp.use { response ->
            if (!response.isSuccessful) return null
            val finalUrl = response.request.url.toString()
            val kwikDomain = "${response.request.url.scheme}://${response.request.url.host}/"
            val kwikReferer = if (response.request.url.host.contains("kwik")) kwikDomain else "https://kwik.cx/"
            val html = response.body?.string() ?: return null
            val unpacked = JsUnpacker.unpack(html)

            // 1. Direct stream extract from unpacked JS or raw HTML
            val directStreamUrl = extractStreamUrl(unpacked) ?: extractStreamUrl(html)
            if (directStreamUrl != null) {
                return StreamTarget(
                    url = directStreamUrl,
                    headers = mapOf(
                        "Referer" to kwikReferer,
                        "User-Agent" to USER_AGENT,
                    ),
                    isAdaptive = directStreamUrl.contains(".m3u8"),
                    label = label,
                )
            }

            // 2. Token POST form resolution if HLS/MP4 is not directly embedded
            val formPair = extractFormActionAndToken(unpacked, html)
            if (formPair != null) {
                val (rawAction, token) = formPair
                val actionUrl = response.request.url.resolve(rawAction)?.toString() ?: rawAction

                val formBody = FormBody.Builder()
                    .add("_token", token)
                    .build()

                val postRequest = Request.Builder()
                    .url(actionUrl)
                    .post(formBody)
                    .header("Referer", finalUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()

                val noRedirectClient = client.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()

                val postResp = runCatching { noRedirectClient.newCall(postRequest).execute() }.getOrNull()
                postResp?.use { pResp ->
                    val location = pResp.header("Location")
                    if (!location.isNullOrBlank()) {
                        val directUrl = pResp.request.url.resolve(location)?.toString() ?: location
                        val cleanedDirectUrl = cleanUrl(directUrl)
                        return StreamTarget(
                            url = cleanedDirectUrl,
                            headers = mapOf(
                                "Referer" to kwikReferer,
                                "User-Agent" to USER_AGENT,
                            ),
                            isAdaptive = cleanedDirectUrl.contains(".m3u8"),
                            label = label,
                        )
                    }
                    if (pResp.isSuccessful) {
                        val postBody = pResp.body?.string() ?: return null
                        val postUnpacked = JsUnpacker.unpack(postBody)
                        val postStreamUrl = extractStreamUrl(postUnpacked) ?: extractStreamUrl(postBody)
                        if (postStreamUrl != null) {
                            return StreamTarget(
                                url = postStreamUrl,
                                headers = mapOf(
                                    "Referer" to kwikReferer,
                                    "User-Agent" to USER_AGENT,
                                ),
                                isAdaptive = postStreamUrl.contains(".m3u8"),
                                label = label,
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractStreamUrl(text: String): String? {
        val sourceVarRegex = Regex(
            """(?i)(?:const|var|let)?\s*source\s*=\s*\\?['"](https?:\\?/\\?/[^'"\s]+)"""
        )
        sourceVarRegex.find(text)?.groupValues?.get(1)?.let { return cleanUrl(it) }

        val quotedMediaRegex = Regex(
            """\\?['"](https?:\\?/\\?/[^'"\s]+\.(?:m3u8|mp4)[^'"\s]*?)\\?['"]""",
            RegexOption.IGNORE_CASE
        )
        quotedMediaRegex.find(text)?.groupValues?.get(1)?.let { return cleanUrl(it) }

        val rawMediaRegex = Regex(
            """https?:\\?/\\?/[^\s'"<>]+\.(?:m3u8|mp4)[^\s'"<>]*""",
            RegexOption.IGNORE_CASE
        )
        rawMediaRegex.find(text)?.value?.let { return cleanUrl(it) }

        return null
    }

    private fun extractFormActionAndToken(vararg sources: String): Pair<String, String>? {
        for (text in sources) {
            val actionMatch = Regex("""<form[^>]+action\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""action\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(text)
            val action = actionMatch?.groupValues?.get(1) ?: continue

            val tokenMatch = Regex("""<input[^>]+name\s*=\s*['"]_token['"][^>]*value\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""<input[^>]+value\s*=\s*['"]([^'"]+)['"][^>]*name\s*=\s*['"]_token['"]""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""name\s*=\s*['"]_token['"]\s+value\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""value\s*=\s*['"]([^'"]+)['"]\s+name\s*=\s*['"]_token['"]""", RegexOption.IGNORE_CASE).find(text)

            val token = tokenMatch?.groupValues?.get(1)
            if (token != null) {
                return Pair(action, token)
            }
        }
        return null
    }

    private fun cleanUrl(raw: String): String {
        return raw.replace("\\/", "/").replace("\\", "").trim('\'', '"')
    }

    private suspend fun get(url: HttpUrl): String {
        return withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()

            val urlsToTry = mutableListOf(url)
            if (url.host in MIRROR_DOMAINS) {
                for (mirror in MIRROR_DOMAINS) {
                    if (mirror != url.host) {
                        urlsToTry.add(url.newBuilder().host(mirror).build())
                    }
                }
            }

            var lastException: Exception? = null
            for (candidateUrl in urlsToTry) {
                coroutineContext.ensureActive()
                try {
                    val request = Request.Builder()
                        .url(candidateUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", "${candidateUrl.scheme}://${candidateUrl.host}/")
                        .build()

                    val resp = client.newCall(request).execute()
                    resp.use {
                        if (it.isSuccessful) {
                            return@withContext it.body?.string() ?: throw IOException("Empty response from Animepahe")
                        } else {
                            lastException = IOException("Animepahe request failed: HTTP ${it.code} on $candidateUrl")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    lastException = e
                }
            }

            throw lastException ?: IOException("Failed to fetch $url across Animepahe mirrors")
        }
    }

    companion object {
        const val ID = "animepahe"
        const val KEY = "builtin-animepahe"
        val MIRROR_DOMAINS = listOf(
            "animepahe.ru",
            "animepahe.org",
            "animepahe.com",
            "animepahe.pw",
            "animepahe.su",
            "animepahe.si",
        )
        val KWIK_DOMAINS = listOf(
            "kwik.cx",
            "kwik.si",
            "kwik.cz",
            "kwik.pw",
        )
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
