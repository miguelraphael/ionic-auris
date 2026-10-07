package com.auris.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.core.model.MediaType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adapter tests, run against the real extension APK.
 *
 * Covers the translation from Anikku's model to Auris's `MediaSource`: pagination shape,
 * episode-number rounding, relative-URL resolution, and the manga presentation path.
 */
@RunWith(AndroidJUnit4::class)
class SourceAdapterTest : ExtensionTestBase() {

    private fun loadAll() = extensionLoader.load(mockExtensionApk(), mockExtensionPackage())

    private fun adapter(name: String = "Mock Anime", mediaType: MediaType = MediaType.ANIME) =
        loadAll()
            .first { it.sourceName == name }
            .toMediaSource(mediaType)

    private fun animeAdapter() = adapter("Mock Anime")

    /** The manga-shaped source, presented as manga through the same single adapter. */
    private fun mangaAdapter() = adapter("Mock Manga", MediaType.MANGA)

    private fun entry(url: String) = SourceItem(url = url, title = "Alpha Series", thumbnailUrl = null)

    // ---- Contract ------------------------------------------------------------

    @Test
    fun adapterSatisfiesMediaSourceContract() {
        val subject: MediaSource = animeAdapter()

        assertEquals("Mock Anime", subject.name)
        assertEquals(MediaType.ANIME, subject.mediaType)
        assertEquals("https://mock.auris.test", subject.baseUrl)
        assertTrue(subject.id.isNotBlank())
    }

    @Test
    fun sameSourceAdaptsToEitherMediaType() {
        // Under Anikku a source is not typed anime-or-manga; the presentation is the
        // caller's choice. This is the collapse made explicit.
        val loaded = loadAll().first { it.sourceName == "Mock Anime" }

        assertEquals(MediaType.ANIME, loaded.toMediaSource(MediaType.ANIME).mediaType)
        assertEquals(MediaType.MANGA, loaded.toMediaSource(MediaType.MANGA).mediaType)
    }

    @Test
    fun mangaAdapterReportsMangaType() {
        assertEquals(MediaType.MANGA, mangaAdapter().mediaType)
        assertEquals("https://mock-manga.auris.test", mangaAdapter().baseUrl)
    }

    // ---- Catalogue -----------------------------------------------------------

    @Test
    fun mapsPopularPageToSourcePage() = runBlocking {
        FakeHttp.on("/api/popular", POPULAR_HTML)
        val page = animeAdapter().popular(1)

        assertEquals(2, page.items.size)
        assertTrue(page.hasNextPage)
        assertEquals("/anime/alpha", page.items[0].url)
        assertEquals("Alpha Series", page.items[0].title)
    }

    @Test
    fun mapsDetailsAndAbsolutisesArtwork() = runBlocking {
        FakeHttp.on("/anime/alpha", DETAILS_HTML)
        val details = animeAdapter().details(entry("/anime/alpha"))

        assertEquals("Alpha Series", details.title)
        assertEquals("Author One", details.author)
        // Relative `originalThumbnailUrl` from the source becomes absolute here.
        assertEquals("https://mock.auris.test/poster/alpha.jpg", details.thumbnailUrl)
    }

    // ---- Units ---------------------------------------------------------------

    @Test
    fun mapsEpisodesToUnits() = runBlocking {
        FakeHttp.on("/anime/alpha/episodes", EPISODES_HTML)
        val units = animeAdapter().units(entry("/anime/alpha"))

        assertEquals(3, units.size)
        assertEquals("/watch/ep1", units[0].url)
        assertEquals("Episode 1", units[0].title)
    }

    @Test
    fun fractionalEpisodeNumbersRoundUpToWholeUnits() = runBlocking {
        FakeHttp.on("/anime/alpha/episodes", EPISODES_HTML)
        val units = animeAdapter().units(entry("/anime/alpha"))

        // Anikku numbers premieres 2.5; Auris units are 1-based integers, and a user expects
        // "episode 2.5" to land after episode 2, not on it.
        assertEquals(1, units[0].index)
        assertEquals(3, units[1].index)
        assertEquals(3, units[2].index)
    }

    @Test
    fun mangaPresentationReadsChapterNumbers() = runBlocking {
        FakeHttp.on("/anime/bravo/episodes", CHAPTERS_HTML)
        val units = mangaAdapter().units(entry("/anime/bravo"))

        assertEquals(2, units.size)
        assertEquals("Chapter 1", units[0].title)
        assertEquals(1, units[0].index)
    }

    // ---- Streams -------------------------------------------------------------

    @Test
    fun mapsVideosToStreamTargets() = runBlocking {
        FakeHttp.on("/anime/alpha/episodes", EPISODES_HTML)
        FakeHttp.on("/watch/ep1", VIDEOS_HTML)

        val adapter = animeAdapter()
        val unit = adapter.units(entry("/anime/alpha")).first { it.url == "/watch/ep1" }
        val streams = adapter.streams(entry("/anime/alpha"), unit)

        assertEquals(2, streams.size)
        assertEquals("https://cdn.auris.test/stream-1080.m3u8", streams[0].url)
        assertEquals("application/x-mpegURL", streams[0].mimeType)
    }

    @Test
    fun mangaPresentationExposesNoStreams() = runBlocking {
        val adapter = mangaAdapter()
        val unit = SourceUnit(url = "/read/bravo-1", index = 1, title = "Chapter 1")

        // Manga has pages, not streams. Empty (rather than a fabricated stream) keeps a caller
        // bug obvious instead of producing a player that cannot play a JPEG.
        assertTrue(adapter.streams(entry("/anime/bravo"), unit).isEmpty())
    }

    // ---- URL resolution ------------------------------------------------------

    @Test
    fun relativeArtworkUrlsAreAbsolutised() {
        // Many extensions scrape with `attr("src")` rather than `absUrl`, so a source can
        // legitimately return "/cover/x.jpg". Coil cannot load that, so the adapter resolves
        // it or the whole source renders with blank artwork.
        assertEquals(
            "https://mock.auris.test/cover/a.jpg",
            resolveUrl("https://mock.auris.test", "/cover/a.jpg"),
        )
        assertEquals(
            "https://mock.auris.test/cover/a.jpg",
            resolveUrl("https://mock.auris.test/", "cover/a.jpg"),
        )
        // Protocol-relative URLs arrive from CDNs constantly.
        assertEquals(
            "https://cdn.auris.test/a.jpg",
            resolveUrl("https://mock.auris.test", "//cdn.auris.test/a.jpg"),
        )
        assertEquals(
            "http://other.test/a.jpg",
            resolveUrl("https://mock.auris.test", "http://other.test/a.jpg"),
        )
        // Null/blank stays null so the UI can fall back to a placeholder.
        assertEquals(null, resolveUrl("https://mock.auris.test", null))
        assertEquals(null, resolveUrl("https://mock.auris.test", "  "))
    }

    // ---- Anikku model shape --------------------------------------------------

    @Test
    fun originalFieldsSurviveTheAdapterBoundary() = runBlocking {
        FakeHttp.on("/anime/alpha", DETAILS_HTML)
        val source = loadAll().first { it.sourceName == "Mock Anime" }.source as AnimeHttpSource

        val anime = source.getAnimeDetails(SAnime.create().apply { url = "/anime/alpha" })

        // Anikku sources read `originalTitle` during parsing; if the host lost that field
        // the source would render blank. Assert it is populated and localised.
        assertEquals("Alpha Series", anime.originalTitle)
        assertEquals("Author One", anime.originalAuthor)
        assertEquals(anime.originalTitle, anime.title)
    }

    @Test
    fun chapterNumberIsAnAliasForEpisodeNumber() {
        val episode = SEpisode.create().apply { chapter_number = 7.5f }

        assertEquals(7.5f, episode.episode_number, 0.001f)
        episode.episode_number = 8f
        assertEquals(8f, episode.chapter_number, 0.001f)
    }

    private companion object {
        val POPULAR_HTML = """
            <html><body>
              <div class="media">
                <a class="link" href="/anime/alpha"></a>
                <div class="title">Alpha Series</div>
                <img class="thumb" src="/cover/alpha.jpg">
              </div>
              <div class="media">
                <a class="link" href="/anime/beta"></a>
                <div class="title">Beta Series</div>
                <img class="thumb" src="/cover/beta.jpg">
              </div>
              <a class="next" href="/popular?page=2">Next</a>
            </body></html>
        """.trimIndent()

        val DETAILS_HTML = """
            <html><body>
              <a class="back" href="/anime"></a>
              <h1 class="title">Alpha Series</h1>
              <span class="author">Author One</span>
              <span class="artist">Studio One</span>
              <div class="description">A story about alpha.</div>
              <span class="status">Airing</span>
              <img class="poster" src="/poster/alpha.jpg">
            </body></html>
        """.trimIndent()

        val EPISODES_HTML = """
            <html><body>
              <div class="episode" data-number="1">
                <a class="play" href="/watch/ep1"></a>
                <span class="name">Episode 1</span>
              </div>
              <div class="episode" data-number="2.5">
                <a class="play" href="/watch/ep25"></a>
                <span class="name">Episode 2.5</span>
              </div>
              <div class="episode" data-number="3">
                <a class="play" href="/watch/ep3"></a>
                <span class="name">Episode 3</span>
              </div>
            </body></html>
        """.trimIndent()

        val VIDEOS_HTML = """
            <html><body>
              <a class="video" href="https://cdn.auris.test/stream-1080.m3u8"
                 data-quality="1080p" data-mime="application/x-mpegURL"></a>
              <a class="video" href="https://cdn.auris.test/stream-720.m3u8"
                 data-quality="720p" data-mime="application/x-mpegURL"></a>
            </body></html>
        """.trimIndent()

        val CHAPTERS_HTML = """
            <html><body>
              <div class="chapter" data-number="1">
                <a class="read" href="/read/bravo-1"></a>
                <span class="name">Chapter 1</span>
              </div>
              <div class="chapter" data-number="2">
                <a class="read" href="/read/bravo-2"></a>
                <span class="name">Chapter 2</span>
              </div>
            </body></html>
        """.trimIndent()
    }
}