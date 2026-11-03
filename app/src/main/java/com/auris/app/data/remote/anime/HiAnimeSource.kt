package com.auris.app.data.remote.anime

import com.auris.app.core.model.MediaType
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.coroutineContext

/** Direct source connector; only the ZokoAnime server protocol is currently supported. */
class HiAnimeSource(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val site: HttpUrl = "https://hianime.at/".toHttpUrl(),
) : MediaSource {
    override val id = ID
    override val name = "HiAnime"
    override val mediaType = MediaType.ANIME
    override val baseUrl = site.toString().trimEnd('/')
    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun popular(page: Int) = catalogue("most-popular", page)
    override suspend fun latest(page: Int) = catalogue("recently-updated", page)
    override suspend fun search(page: Int, query: String, filters: FilterList) = catalogue("search", page, query)
    override suspend fun details(entry: SourceItem) = entry

    private suspend fun catalogue(path: String, page: Int, query: String? = null): SourcePage {
        require(page > 0)
        val url = site.newBuilder().addPathSegment(path).addQueryParameter("page", page.toString())
        if (query != null) url.addQueryParameter("keyword", query.trim())
        val doc = Jsoup.parse(get(url.build()), site.toString())
        doc.select("#main-sidebar").remove()
        val items = doc.select(".flw-item").mapNotNull { card ->
            val anchor = card.selectFirst(".film-name a[href]") ?: return@mapNotNull null
            val title = anchor.attr("title").ifBlank { anchor.text() }
            val slug = anchor.absUrl("href").toHttpUrlOrNull()?.pathSegments?.lastOrNull()
                ?.takeIf { it.matches(Regex("[a-zA-Z0-9-]+-\\d+")) } ?: return@mapNotNull null
            val image = card.selectFirst("img.film-poster-img")
            val artwork = image?.let { img ->
                sequenceOf("data-src", "src").map { img.absUrl(it) }.firstOrNull { it.startsWith("http") }
            }
            SourceItem(slug, title, artwork)
        }.distinctBy { it.url }
        if (items.isEmpty() && doc.selectFirst(".film_list-wrap") == null) {
            throw IOException("HiAnime did not return a readable catalogue. The source may be unavailable.")
        }
        val hasNext = doc.select(".pagination a[href]").any { link ->
            (link.absUrl("href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull() ?: 0) > page
        }
        return SourcePage(items, hasNext)
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val showId = entry.url.substringAfterLast('-').takeIf { it.all(Char::isDigit) && it.isNotBlank() }
            ?: throw IOException("Invalid anime identity")
        val result = json(site.newBuilder().addPathSegments("api/theme/episode/list").addPathSegment(showId).build())
        val doc = Jsoup.parse(result.string("html") ?: throw IOException("Missing episode list"), site.toString())
        return doc.select(".ep-item[data-id]").distinctBy { it.attr("data-id") }.mapIndexed { index, episode ->
            val number = episode.attr("data-number").ifBlank { (index + 1).toString() }
            val title = episode.selectFirst(".ep-name")?.text().orEmpty().ifBlank { "Episode $number" }
            SourceUnit(episode.attr("data-id"), index + 1,
                if (title == "Episode $number") title else "Episode $number · $title")
        }
    }

    override suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget> = coroutineScope {
        require(unit.url.isNotBlank() && unit.url.all(Char::isDigit)) { "Invalid episode identity" }
        val response = json(site.newBuilder().addPathSegments("api/theme/episode/servers")
            .addQueryParameter("episodeId", unit.url).build())
        val doc = Jsoup.parse(response.string("html") ?: throw IOException("Missing episode servers"))
        val servers = doc.select(".server-item[data-hash]").filter {
            it.attr("data-server-name").equals("ZokoAnime", true) && it.attr("data-type") in listOf("sub", "dub")
        }.distinctBy { it.attr("data-type") }.sortedBy { if (it.attr("data-type") == "sub") 0 else 1 }
        if (servers.isEmpty()) throw IOException("No supported server is available for this episode")
        val results = servers.map { server -> async {
            try {
                Result.success(resolveEmbed(server.attr("data-hash"), server.attr("data-type")))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { Result.failure<StreamTarget>(error) }
        } }.awaitAll()
        results.mapNotNull { it.getOrNull() }.ifEmpty {
            throw IOException("The episode server could not be reached. Please retry.", results.first().exceptionOrNull())
        }
    }

    private suspend fun resolveEmbed(encodedUrl: String, translation: String): StreamTarget {
        val embed = String(Base64.getDecoder().decode(encodedUrl), Charsets.UTF_8).toHttpUrl()
        // Server entries come from the source, but only the tested embed protocol is understood.
        if (site.isHttps && (!embed.isHttps || embed.host != "zokoanime.video")) {
            throw IOException("Unsupported episode embed host")
        }
        val page = get(embed)
        val blob = Regex("window\\.__P\\s*=\\s*\"([^\"]+)\"").find(page)?.groupValues?.get(1)
            ?: throw IOException("The episode server format has changed")
        val bytes = Base64.getDecoder().decode(blob)
        val key = "otaku-embed-v1".toByteArray(Charsets.UTF_8)
        val decoded = ByteArray(bytes.size) { (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() }
        val config = Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
        val stream = config.string("src")?.toHttpUrlOrNull() ?: throw IOException("Missing video playlist")
        if (site.isHttps && !stream.isHttps) throw IOException("Insecure video playlist")
        val referer = embed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
        val subtitles = (config["subtitles"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val url = track.string("src")?.toHttpUrlOrNull() ?: return@mapNotNull null
            if (site.isHttps && !url.isHttps) return@mapNotNull null
            SubtitleTrack(url.toString(), track.string("lang"), track.string("label") ?: "Subtitles",
                isDefault = (track["default"] as? JsonPrimitive)?.booleanOrNull ?: false,
                mimeType = if (url.encodedPath.endsWith(".srt")) "application/x-subrip" else "text/vtt")
        }
        return StreamTarget(stream.toString(), mapOf("Referer" to referer, "User-Agent" to USER_AGENT),
            mimeType = "application/x-mpegURL", isAdaptive = true,
            label = "ZokoAnime · ${if (translation == "sub") "Sub" else "Dub"}", subtitles = subtitles)
    }

    private suspend fun json(url: HttpUrl): JsonObject {
        val result = Json.parseToJsonElement(get(url)).jsonObject
        if ((result["status"] as? JsonPrimitive)?.booleanOrNull != true) throw IOException("The anime source rejected this request")
        return result
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", site.toString()).build()
        client.newCall(request).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw IOException("Anime source request failed (HTTP ${response.code})")
            response.body?.string() ?: throw IOException("Empty anime source response")
        }
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    companion object {
        const val ID = "hianime"
        const val KEY = "builtin-hianime"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
