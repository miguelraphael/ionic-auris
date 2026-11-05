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
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Independent English manga provider; identities stay scoped to MangaPill. */
class MangaPillSource(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val site: HttpUrl = "https://mangapill.com/".toHttpUrl(),
) : MediaSource, MangaReaderSource {
    override val id = ID
    override val name = "MangaPill"
    override val mediaType = MediaType.MANGA
    override val baseUrl = site.toString().trimEnd('/')
    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit
    override suspend fun streams(entry: SourceItem, unit: SourceUnit) = emptyList<StreamTarget>()

    override suspend fun popular(page: Int): SourcePage {
        require(page > 0)
        if (page > 1) return SourcePage(emptyList(), false)
        val doc = get(site)
        val header = doc.select("h4").firstOrNull { it.text() == "Trending Mangas" }
            ?: throw IOException("MangaPill did not return its trending list. Please retry.")
        return SourcePage(cards(header.parent()?.nextElementSibling()
            ?: throw IOException("Missing MangaPill trending list")), false)
    }

    override suspend fun latest(page: Int): SourcePage {
        require(page > 0)
        if (page > 1) return SourcePage(emptyList(), false)
        val doc = get(site)
        val header = doc.select("h4").firstOrNull { it.text() == "New Chapters" }
            ?: throw IOException("MangaPill did not return its latest chapters. Please retry.")
        return SourcePage(cards(header.nextElementSibling()
            ?: throw IOException("Missing MangaPill latest list")), false)
    }

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0)
        val doc = get(site.newBuilder().addPathSegment("search")
            .addQueryParameter("q", query.trim()).addQueryParameter("page", page.toString()).build())
        if (doc.selectFirst("form input[name=q]") == null) throw IOException("MangaPill search is unavailable")
        val more = doc.select("a[href]").any {
            val link = site.resolve(it.attr("href"))
            link?.encodedPath == "/search" && (link.queryParameter("page")?.toIntOrNull() ?: 0) > page
        }
        return SourcePage(cards(doc), more)
    }

    private fun cards(root: Element): List<SourceItem> = root.select("a[href]").mapNotNull { link ->
        val path = path(link.attr("href"), "/manga/") ?: return@mapNotNull null
        val card = link.parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.select("a[href]")
                .mapNotNull { path(it.attr("href"), "/manga/") }.distinct().size == 1
        } ?: return@mapNotNull null
        val title = card.select("a[href]").firstOrNull {
            path(it.attr("href"), "/manga/") == path && it.text().isNotBlank()
        }?.text() ?: return@mapNotNull null
        val img = card.selectFirst("img")
        SourceItem(path, title, image(img))
    }.distinctBy { it.url }

    override suspend fun details(entry: SourceItem): SourceItem {
        val doc = get(url(entry.url, "/manga/"))
        val title = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw IOException("MangaPill did not return this title")
        return entry.copy(title = title, thumbnailUrl = image(doc.selectFirst("img[data-src]")) ?: entry.thumbnailUrl)
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val doc = get(url(entry.url, "/manga/"))
        val chapters = doc.selectFirst("#chapters") ?: throw IOException("MangaPill did not return the chapter list")
        return chapters.select("a[href]").mapNotNull { link ->
            path(link.attr("href"), "/chapters/")?.let { it to link.text() }
        }.distinctBy { it.first }.reversed().mapIndexed { index, (path, title) ->
            SourceUnit(path, index + 1, title.ifBlank { "Chapter ${index + 1}" })
        }
    }

    override suspend fun pages(entry: SourceItem, unit: SourceUnit): List<Page> {
        val doc = get(url(unit.url, "/chapters/"))
        return doc.select("img.js-page").mapNotNull(::image).mapIndexed { index, url -> Page(index, url, url, mapOf("Referer" to site.toString(), "User-Agent" to "Mozilla/5.0")) }
            .ifEmpty { throw IOException("MangaPill did not return readable chapter images. Please retry.") }
    }

    private fun image(img: Element?): String? = img?.let {
        sequenceOf("data-src", "src").mapNotNull { attr ->
            it.attr(attr).takeIf(String::isNotBlank)?.let(site::resolve)?.takeIf { url -> url.isHttps || !site.isHttps }?.toString()
        }.firstOrNull()
    }
    private fun path(value: String, prefix: String): String? = site.resolve(value)?.takeIf {
        it.host == site.host && it.encodedPath.startsWith(prefix) && it.pathSegments.size >= 3
    }?.encodedPath
    private fun url(value: String, prefix: String): HttpUrl = site.resolve(path(value, prefix)
        ?: throw IOException("Invalid MangaPill title or chapter identity"))!!
    private suspend fun get(url: HttpUrl) = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
            .header("Referer", site.toString()).build()).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw IOException("MangaPill request failed (HTTP ${response.code})")
            Jsoup.parse(response.body?.string() ?: throw IOException("Empty MangaPill response"), site.toString())
        }
    }
    companion object { const val ID = "mangapill" }
}
