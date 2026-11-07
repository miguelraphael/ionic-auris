package com.auris.app.data.remote.anime

import com.auris.app.core.model.MediaType
import com.auris.app.source.*
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Built-in connector for Animegg (https://www.animegg.org/).
 * Provides Japanese anime catalog (subbed and dubbed) with direct MP4 streams.
 */
class AnimeggSource(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build(),
    private val site: HttpUrl = "https://www.animegg.org/".toHttpUrl(),
) : MediaSource {
    override val id = ID
    override val name = "Animegg"
    override val mediaType = MediaType.ANIME
    override val baseUrl = site.toString().trimEnd('/')

    override suspend fun configuration() = emptyMap<String, String>()
    override suspend fun updateConfiguration(values: Map<String, String>) = Unit

    override suspend fun popular(page: Int): SourcePage {
        require(page > 0)
        val start = (page - 1) * 25
        val url = site.newBuilder().addPathSegment("popular-series")
            .addQueryParameter("start", start.toString())
            .build()
        val doc = Jsoup.parse(get(url), site.toString())
        val items = doc.select(".rightpop a[href^=\"/series/\"], .fealink[href^=\"/series/\"]").mapNotNull { link ->
            val path = path(link.attr("href"))?.takeIf { it.startsWith("/series/") } ?: return@mapNotNull null
            val title = link.text().trim().takeIf { it.isNotBlank() && it != "Watch Anime" }
                ?: link.closest(".rightpop")?.selectFirst("a[href^=\"/series/\"]")?.text()?.trim()
                ?: return@mapNotNull null
            val card = link.closest(".fea") ?: link.closest("li") ?: link.parent()?.parent()
            val image = card?.selectFirst("img")?.let { img ->
                sequenceOf("data-src", "src").mapNotNull { img.attr(it).takeIf(String::isNotBlank)?.let(site::resolve)?.toString() }.firstOrNull()
            }
            SourceItem(path, title, image)
        }.distinctBy { it.url }

        val hasNext = doc.select(".pagination a").any { it.text().contains("Next", ignoreCase = true) }
        return SourcePage(items, hasNext)
    }

    override suspend fun latest(page: Int): SourcePage {
        require(page > 0)
        val start = (page - 1) * 10
        val url = site.newBuilder().addPathSegment("releases")
            .addQueryParameter("start", start.toString())
            .build()
        val doc = Jsoup.parse(get(url), site.toString())
        val items = doc.select("li.fea.release, .rightpop.release").mapNotNull { card ->
            val link = card.selectFirst("a.releaseLink[href^=\"/series/\"]")
                ?: card.selectFirst("a[href^=\"/series/\"]")
                ?: return@mapNotNull null
            val path = path(link.attr("href"))?.takeIf { it.startsWith("/series/") } ?: return@mapNotNull null
            val title = link.text().trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val image = card.selectFirst("img")?.let { img ->
                sequenceOf("data-src", "src").mapNotNull { img.attr(it).takeIf(String::isNotBlank)?.let(site::resolve)?.toString() }.firstOrNull()
            }
            SourceItem(path, title, image)
        }.distinctBy { it.url }

        val hasNext = doc.select(".pagination a").any { it.text().contains("Next", ignoreCase = true) }
        return SourcePage(items, hasNext)
    }

    override suspend fun search(page: Int, query: String, filters: FilterList): SourcePage {
        require(page > 0)
        val url = site.newBuilder().addPathSegment("search")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("start", ((page - 1) * 25).toString())
            .build()
        val doc = Jsoup.parse(get(url), site.toString())
        val items = doc.select("a.mse[href^=\"/series/\"]").mapNotNull { link ->
            val path = path(link.attr("href"))?.takeIf { it.startsWith("/series/") } ?: return@mapNotNull null
            val title = link.selectFirst("h2")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: link.text().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val image = link.selectFirst("img")?.let { img ->
                sequenceOf("data-src", "src").mapNotNull { img.attr(it).takeIf(String::isNotBlank)?.let(site::resolve)?.toString() }.firstOrNull()
            }
            SourceItem(path, title, image)
        }.distinctBy { it.url }

        val hasNext = doc.select(".pagination a").any { it.text().contains("Next", ignoreCase = true) }
        return SourcePage(items, hasNext)
    }

    override suspend fun details(entry: SourceItem): SourceItem {
        val doc = Jsoup.parse(get(url(entry.url)), site.toString())
        val title = doc.selectFirst(".media-body h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: entry.title
        val image = doc.selectFirst(".media .pull-left img, .media-object")?.let { img ->
            sequenceOf("data-src", "src").mapNotNull { img.attr(it).takeIf(String::isNotBlank)?.let(site::resolve)?.toString() }.firstOrNull()
        } ?: entry.thumbnailUrl
        return entry.copy(title = title, thumbnailUrl = image)
    }

    override suspend fun units(entry: SourceItem): List<SourceUnit> {
        val doc = Jsoup.parse(get(url(entry.url)), site.toString())
        val list = doc.select("a.anm_det_pop[href], a[href*=\"-episode-\"]").mapNotNull { a ->
            val href = path(a.attr("href"))?.takeIf { it.contains("-episode-") } ?: return@mapNotNull null
            val name = a.text().trim().ifBlank { a.attr("title").trim() }
            val subtitle = a.parent()?.selectFirst("i.anititle")?.text()?.trim()
            val fullTitle = if (!subtitle.isNullOrBlank()) "$name · $subtitle" else name
            href to fullTitle
        }.distinctBy { it.first }

        if (list.isEmpty()) throw IOException("Animegg did not return an episode list")
        return list.reversed().mapIndexed { index, (epPath, epTitle) ->
            SourceUnit(epPath, index + 1, epTitle.ifBlank { "Episode ${index + 1}" })
        }
    }

    override suspend fun streams(entry: SourceItem, unit: SourceUnit): List<StreamTarget> {
        val epDoc = Jsoup.parse(get(url(unit.url)), site.toString())
        val embedPath = epDoc.selectFirst("iframe.video[src]")?.attr("src")
            ?: epDoc.selectFirst("iframe[src*=\"/embed/\"]")?.attr("src")
            ?: epDoc.selectFirst("iframe[src]")?.attr("src")
            ?: throw IOException("No video player found for this episode")

        val embedUrl = site.resolve(embedPath) ?: throw IOException("Invalid embed URL: $embedPath")
        val embedHtml = get(embedUrl, referer = url(unit.url).toString())

        val streamRegex = Regex("""file:\s*["']([^"']+)["'],\s*label:\s*["']([^"']+)["']""")
        val matches = streamRegex.findAll(embedHtml).toList()
        val targets = matches.mapNotNull { m ->
            val file = m.groupValues[1]
            val label = m.groupValues[2]
            val resolvedUrl = site.resolve(file)?.toString() ?: return@mapNotNull null
            StreamTarget(
                url = resolvedUrl,
                headers = mapOf(
                    "Referer" to site.toString(),
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                ),
                mimeType = "video/mp4",
                label = "Animegg · $label",
            )
        }

        if (targets.isNotEmpty()) {
            return targets.sortedByDescending { target ->
                Regex("""\d+""").find(target.label)?.value?.toIntOrNull() ?: 0
            }
        }

        // Fallback: check backup 'bk' in embed
        val bkRegex = Regex("""bk:\s*["']([^"']+)["']""")
        val bkMatches = bkRegex.findAll(embedHtml).toList()
        val bkTargets = bkMatches.mapNotNull { m ->
            val b64 = m.groupValues[1]
            val decoded = runCatching {
                val raw = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
                java.net.URLDecoder.decode(raw, "UTF-8")
            }.getOrNull()?.toHttpUrlOrNull() ?: return@mapNotNull null
            StreamTarget(
                url = decoded.toString(),
                headers = mapOf(
                    "Referer" to site.toString(),
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                ),
                mimeType = "video/mp4",
                label = "Animegg · Backup",
            )
        }

        if (bkTargets.isNotEmpty()) return bkTargets
        throw IOException("No playable streams found for this episode")
    }

    private fun path(value: String): String? = site.resolve(value)?.takeIf { it.host == site.host }?.encodedPath
    private fun url(value: String): HttpUrl = site.resolve(path(value) ?: throw IOException("Invalid Animegg identity: $value"))!!

    private suspend fun get(url: HttpUrl, referer: String = site.toString()): String = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Referer", referer)
                .build()
        ).execute().use { response ->
            coroutineContext.ensureActive()
            if (!response.isSuccessful) throw IOException("Animegg server request failed (HTTP ${response.code})")
            response.body?.string() ?: throw IOException("Empty Animegg response")
        }
    }

    companion object {
        const val ID = "animegg"
    }
}
