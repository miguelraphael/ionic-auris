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

/** Chinese animation catalog; supports the site's public English-subtitled OK.ru embeds. */
class AnimeKhorSource(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val site: HttpUrl = "https://animekhor.org/".toHttpUrl(),
    private val videoHost: HttpUrl = "https://ok.ru/".toHttpUrl(),
) : MediaSource {
    override val id = ID
    override val name = "AnimeKhor"
    override val mediaType = MediaType.ANIME
    override val baseUrl = site.toString().trimEnd('/')
    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun popular(page: Int) = catalogue(page, "popular")
    override suspend fun latest(page: Int) = catalogue(page, "update")
    override suspend fun search(page: Int, query: String, filters: FilterList) = catalogue(page, null, query)

    private suspend fun catalogue(page: Int, order: String?, query: String? = null): SourcePage {
        require(page > 0)
        val url = site.newBuilder().addPathSegment("anime").addPathSegment("")
            .addQueryParameter("page", page.toString())
        order?.let { url.addQueryParameter("order", it) }
        query?.let { url.addQueryParameter("s", it.trim()) }
        val doc = Jsoup.parse(get(url.build()), site.toString())
        val list = doc.selectFirst(".listupd") ?: throw IOException("AnimeKhor did not return a readable catalogue")
        val items = list.select(".bsx > a[href]").mapNotNull { link ->
            val path = path(link.attr("href"))?.takeIf { it.startsWith("/anime/") } ?: return@mapNotNull null
            val title = link.attr("title").ifBlank { link.selectFirst(".tt")?.text().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val image = link.selectFirst("img")?.let { img ->
                sequenceOf("data-src", "src").mapNotNull { img.attr(it).takeIf(String::isNotBlank)?.let(site::resolve)?.toString() }.firstOrNull()
            }
            SourceItem(path, title, image)
        }.distinctBy { it.url }
        val more = doc.select(".hpage a[href]").any {
            (site.resolve(it.attr("href"))?.queryParameter("page")?.toIntOrNull() ?: 0) > page
        }
        return SourcePage(items, more)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val doc = Jsoup.parse(get(url(entry.url)), site.toString())
        return entry.copy(title = doc.selectFirst("h1.entry-title")?.text()?.takeIf(String::isNotBlank)
            ?: throw IOException("AnimeKhor did not return this title"))
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val doc = Jsoup.parse(get(url(entry.url)), site.toString())
        val list = doc.selectFirst(".eplister") ?: throw IOException("AnimeKhor did not return an episode list")
        return list.select("li a[href]").mapNotNull { link ->
            path(link.attr("href"))?.let { it to (link.selectFirst(".epl-title")?.text() ?: link.text()) }
        }.distinctBy { it.first }.reversed().mapIndexed { index, (path, title) ->
            SourceUnit(path, index + 1, title.ifBlank { "Episode ${index + 1}" })
        }
    }

    override suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget> {
        val doc = Jsoup.parse(get(url(unit.url)), site.toString())
        val embeds = doc.select("select.mirror option[value]").filter { it.text().contains("[ENGLISH]", true) }
            .mapNotNull { option ->
                val html = runCatching { String(Base64.getDecoder().decode(option.attr("value")), Charsets.UTF_8) }.getOrNull()
                    ?: return@mapNotNull null
                Jsoup.parse(html).selectFirst("iframe[src]")?.attr("src")?.let(site::resolve)
                    ?.takeIf { it.host == videoHost.host && it.encodedPath.matches(Regex("/videoembed/\\d+")) }
            }.distinctBy { it.encodedPath }
        if (embeds.isEmpty()) throw IOException("This episode has no supported English-subtitled OK.ru server")
        for (embed in embeds) {
            coroutineContext.ensureActive()
            try {
                val player = Jsoup.parse(get(embed)).selectFirst("[data-options]")
                    ?: throw IOException("The episode host did not return player data")
                val options = Json.parseToJsonElement(player.attr("data-options")).jsonObject
                val raw = options["flashvars"]?.jsonObject?.get("metadata")
                    ?: throw IOException("Missing episode video metadata")
                val metadata = if (raw is JsonObject) raw else Json.parseToJsonElement(raw.jsonPrimitive.content).jsonObject
                val targets = (metadata["videos"] as? JsonArray).orEmpty().mapNotNull { value ->
                    val video = value.jsonObject
                    val address = video["url"]?.jsonPrimitive?.contentOrNull?.toHttpUrlOrNull()
                        ?.takeIf { it.isHttps || !site.isHttps } ?: return@mapNotNull null
                    val quality = video["name"]?.jsonPrimitive?.contentOrNull ?: "Auto"
                    StreamTarget(address.toString(), mapOf("Referer" to videoHost.toString(), "User-Agent" to "Mozilla/5.0"),
                        mimeType = "video/mp4", label = "OK.ru · $quality")
                }.reversed()
                if (targets.isNotEmpty()) return targets
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { /* Another source-provided OK.ru mirror may still work. */ }
        }
        throw IOException("AnimeKhor's episode servers could not be reached. Retry or choose another source.")
    }

    private fun path(value: String): String? = site.resolve(value)?.takeIf { it.host == site.host }?.encodedPath
    private fun url(value: String): HttpUrl = site.resolve(path(value) ?: throw IOException("Invalid AnimeKhor identity"))!!
    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
            .header("Referer", site.toString()).build()).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw IOException("AnimeKhor server request failed (HTTP ${response.code})")
            response.body?.string() ?: throw IOException("Empty AnimeKhor response")
        }
    }
    companion object { const val ID = "animekhor" }
}
