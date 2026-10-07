package com.auris.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.core.model.MediaType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the extension pipeline end to end on a real device:
 *
 * ```
 * mock-extension APK -> DexClassLoader -> factory reflection -> ParsedAnimeHttpSource
 *   -> canned HTTP -> Jsoup parse -> domain models -> AnimeSourceAdapter -> MediaSource
 * ```
 *
 * This is the only test that can prove the loading mechanism, because `DexClassLoader` has no
 * JVM/Robolectric equivalent — it needs a real dalvik VM.
 *
 * The mock extension is written against the **Anikku** API, so a pass here means an Anikku
 * extension would load too.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionLoaderTest : ExtensionTestBase() {

    private fun loadAll() = extensionLoader.load(mockExtensionApk(), mockExtensionPackage())

    private fun animeSource(name: String = "Mock Anime", lang: String = "en"): AnimeHttpSource =
        loadAll()
            .first { it.sourceName == name && (lang == "en" || it.source.lang == lang) }
            .source as AnimeHttpSource

    // ---- Loading -------------------------------------------------------------

    @Test
    fun loadsSourcesFromRealApk() {
        val sources = loadAll()

        // 2 anime sources (en + de) plus one manga-shaped source, all from one extension.
        assertEquals("Expected 3 sources from the mock extension", 3, sources.size)
        assertEquals(setOf(mockExtensionPackage()), extensionLoader.loadedPackages)
    }

    @Test
    fun discoversFactoriesByInterfaceNotAnnotation() {
        loadAll()

        // Discovery is by interface, so no @ExtensionSourceFactories annotation is needed.
        assertEquals(3, extensionLoader.sources.size)
        assertTrue(
            "Manga-shaped source must be found through the anime hierarchy",
            extensionLoader.sources.any { it.sourceName == "Mock Manga" },
        )
    }

    @Test
    fun loadedSourcesExtendHostApiTypes() {
        val source: Class<*> = animeSource().javaClass

        // The concrete class comes from the DexClassLoader (the extension's own code); the
        // `eu.kanade.tachiyomi.*` chain must come from the host, or nothing could cross the
        // boundary.
        assertTrue(source.classLoader is dalvik.system.DexClassLoader)
        assertTrue(
            "AnimeHttpSource must be host-loaded, not extension-loaded",
            AnimeHttpSource::class.java.classLoader !is dalvik.system.DexClassLoader,
        )
        assertTrue(
            "The extension must extend the host's AnimeHttpSource",
            AnimeHttpSource::class.java.isAssignableFrom(source),
        )
    }

    @Test
    fun hostAndExtensionShareModelClasses() {
        // Type identity, not assignability: if both sides resolved
        // `eu.kanade.tachiyomi.animesource.model.SAnime` to the same definition, an
        // extension's SAnime can be handed to the host without reflection.
        val extensionClass = animeSource().javaClass.classLoader
            .loadClass("eu.kanade.tachiyomi.animesource.model.SAnime")
        val hostClass = Class.forName("eu.kanade.tachiyomi.animesource.model.SAnime")

        assertEquals(hostClass, extensionClass)
    }

    @Test
    fun missingApkYieldsNoSourcesRatherThanCrashing() {
        val sources = extensionLoader.load(java.io.File("/does/not/exist.apk"), "com.example.missing")

        // A bad extension must never take down the others.
        assertTrue(sources.isEmpty())
    }

    @Test
    fun nonApkFileYieldsNoSources() {
        val junk = java.io.File.createTempFile("not-an-apk", ".apk").apply {
            writeText("this is not a zip archive")
        }
        junk.deleteOnExit()

        assertTrue(extensionLoader.load(junk, "com.example.junk").isEmpty())
    }

    @Test
    fun reloadingSameApkIsIdempotent() {
        val apk = mockExtensionApk()

        assertEquals(3, extensionLoader.load(apk, mockExtensionPackage()).size)
        assertEquals(3, extensionLoader.load(apk, mockExtensionPackage()).size)
    }

    @Test
    fun everyLoadedSourceAdaptsToMediaSource() {
        loadAll()

        // Anikku unifies anime and manga, so there is one adapter and the media type is a
        // presentation choice rather than something inferred from the class.
        extensionLoader.sources.forEach { loaded ->
            listOf(MediaType.ANIME, MediaType.MANGA).forEach { mediaType ->
                val adapter = loaded.toMediaSource(mediaType)
                assertTrue(adapter.name.isNotBlank())
                assertTrue(adapter.id.isNotBlank())
                assertEquals(mediaType, adapter.mediaType)
            }
        }
    }

    // ---- Source identity -----------------------------------------------------

    @Test
    fun sourceIdsAreStableAcrossLoads() {
        val apk = mockExtensionApk()

        val first = loadAll().associate { it.sourceName to it.aniyomiId }
        extensionLoader.unloadAll()
        extensionLoader.clearCache()

        val second = extensionLoader.load(apk, mockExtensionPackage())
            .associate { it.sourceName to it.aniyomiId }

        // Ids feed `library_entries.source_key`, so they must be reproducible across process
        // restarts or every user's library would fork on each launch.
        assertEquals(first, second)
    }

    @Test
    fun sourcesWithDifferentLanguageHaveDifferentIds() {
        val sources = loadAll()
        val en = sources.first { it.source.lang == "en" && it.sourceName == "Mock Anime" }
        val de = sources.first { it.source.lang == "de" }

        assertEquals("Mock Anime", en.sourceName)
        assertEquals("Mock Anime", de.sourceName)
        assertNotEquals(en.aniyomiId, de.aniyomiId)
    }

    @Test
    fun sourceIdMatchesAnikkuMd5Algorithm() {
        val en = loadAll().first { it.source.lang == "en" && it.sourceName == "Mock Anime" }

        // Independently recompute Anikku's documented algorithm:
        // MD5("name/lang/versionId"), first 8 bytes big-endian, sign bit cleared.
        val key = "${"Mock Anime".lowercase()}/en/1"
        val digest = java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray())
        val expected = (0..7)
            .map { digest[it].toLong() and 0xffL shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE

        assertEquals(expected, en.aniyomiId)
    }

    // ---- Parsing -------------------------------------------------------------

    @Test
    fun parsesPopularCatalogue() = runBlocking {
        FakeHttp.on("/api/popular", POPULAR_HTML)
        val page = animeSource().getPopularAnime(1)

        assertEquals(2, page.animes.size)
        assertEquals("Alpha Series", page.animes[0].title)
        assertEquals("/anime/alpha", page.animes[0].url)
        // Anikku sources populate `originalTitle`; the localised twin is derived from it.
        assertEquals("Alpha Series", page.animes[0].originalTitle)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun parsesAnimeDetails() = runBlocking {
        FakeHttp.on("/anime/alpha", DETAILS_HTML)
        val anime = animeSource().getAnimeDetails(SAnime.create().apply { url = "/anime/alpha" })

        assertEquals("Alpha Series", anime.title)
        assertEquals("Author One", anime.author)
        assertEquals("Author One", anime.originalAuthor)
        assertEquals("Studio One", anime.artist)
        assertEquals("A story about alpha.", anime.description)
        assertEquals(SAnime.ONGOING, anime.status)
        assertEquals(listOf("Action", "Sci-Fi"), anime.getGenres())
        assertTrue(anime.initialized)
    }

    @Test
    fun parsesEpisodeListWithChapterAlias() = runBlocking {
        FakeHttp.on("/anime/alpha/episodes", EPISODES_HTML)
        val episodes = animeSource().getEpisodeList(
            SAnime.create().apply { url = "/anime/alpha" },
        )

        assertEquals(3, episodes.size)
        assertEquals("Episode 1", episodes[0].name)
        assertEquals(1f, episodes[0].episode_number, 0.001f)
        // `chapter_number` is the manga-spelled alias over `episode_number`.
        assertEquals(episodes[0].episode_number, episodes[0].chapter_number, 0.001f)
        assertEquals(2.5f, episodes[1].episode_number, 0.001f)
    }

    @Test
    fun parsesSeasonList() = runBlocking {
        FakeHttp.on("/anime/alpha/seasons", SEASONS_HTML)
        val seasons = animeSource().getSeasonList(
            SAnime.create().apply { url = "/anime/alpha" },
        )

        // getSeasonList replaced getAnimeEpisodeUpdate in Anikku; it must work.
        assertEquals(2, seasons.size)
        assertEquals("Season 1", seasons[0].title)
    }

    @Test
    fun parsesVideoList() = runBlocking {
        FakeHttp.on("/watch/ep1", VIDEOS_HTML)
        val videos = animeSource().getVideoList(
            SEpisode.create().apply { url = "/watch/ep1" },
        )

        assertEquals(2, videos.size)
        assertEquals("https://cdn.auris.test/stream-1080.m3u8", videos[0].url)
        assertEquals("1080p", videos[0].quality)
        assertEquals("application/x-mpegURL", videos[0].mimeType)
    }

    // ---- Manga-shaped source -------------------------------------------------

    @Test
    fun mangaShapedSourceLoadsAndParsesChapters() = runBlocking {
        FakeHttp.on("/anime/bravo/episodes", CHAPTERS_HTML)
        val source = animeSource(name = "Mock Manga")

        // Same class hierarchy; only the markup and presentation differ.
        assertTrue(source is AnimeHttpSource)
        val chapters = source.getEpisodeList(SAnime.create().apply { url = "/anime/bravo" })

        assertEquals(2, chapters.size)
        assertEquals("Chapter 1", chapters[0].name)
        assertEquals(1f, chapters[0].chapter_number, 0.001f)
    }

    // ---- Failure containment -------------------------------------------------

    @Test
    fun networkFailurePropagatesAsIoException() = runBlocking {
        FakeHttp.failureMessage = "connection reset"
        val source = animeSource()

        val error = runCatching { source.getPopularAnime(1) }.exceptionOrNull()

        // Must not be swallowed: the UI distinguishes "failed" from "no results".
        assertTrue(
            "Expected IOException, got ${error?.javaClass?.simpleName}",
            error is java.io.IOException,
        )
        assertTrue(error!!.message!!.contains("connection reset"))
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
              <ul><li class="genre">Action</li><li class="genre">Sci-Fi</li></ul>
              <span class="status">Airing</span>
              <img class="poster" src="/poster/alpha.jpg">
            </body></html>
        """.trimIndent()

        val EPISODES_HTML = """
            <html><body>
              <div class="episode" data-number="1">
                <a class="play" href="/watch/ep1"></a>
                <span class="name">Episode 1</span>
                <img class="thumb" src="/thumb/ep1.jpg">
              </div>
              <div class="episode" data-number="2.5">
                <a class="play" href="/watch/ep25"></a>
                <span class="name">Episode 2.5</span>
                <img class="thumb" src="/thumb/ep2.jpg">
              </div>
              <div class="episode" data-number="3">
                <a class="play" href="/watch/ep3"></a>
                <span class="name">Episode 3</span>
              </div>
            </body></html>
        """.trimIndent()

        val SEASONS_HTML = """
            <html><body>
              <div class="season">
                <a class="link" href="/anime/alpha?s=1"></a>
                <span class="name">Season 1</span>
              </div>
              <div class="season">
                <a class="link" href="/anime/alpha?s=2"></a>
                <span class="name">Season 2</span>
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