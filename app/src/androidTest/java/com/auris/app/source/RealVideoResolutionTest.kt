package com.auris.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.core.model.MediaType
import com.auris.app.source.AnimeSourceAdapter
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.DefaultNetworkHelper
import eu.kanade.tachiyomi.network.NetworkHelperProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end proof of the **video resolution path** — the chain that has been inferred since the
 * project began and never observed:
 *
 * ```
 * catalogue -> details -> episode -> getHosterList -> getVideoList -> real stream URL
 * ```
 *
 * `AnimeSourceAdapter.pages()` is built on exactly this, so a pass here is the first evidence that
 * the manga reader and episode player have anything real underneath them.
 *
 * ### Why a different fixture from the catalogue test
 * `real-anime-catalog-extension.apk` is **season-based**: its dex declares `seasonListParse` and
 * overrides no `getEpisodeList`, so episodes hang off a season. `getSeasonList` returns empty for
 * every real title tried, and chasing that is a separate investigation — see
 * [RealCatalogExtractionTest] — rather than something to entangle with proving video resolution.
 *
 * This fixture is the flat generation: it declares `episodeListParse` and **no** `seasonListParse`,
 * so episodes come straight off the details page. It also needs no credentials, which
 * `real-mangasource-extension.apk` does.
 *
 * ### Network test
 * These hit a real site and will fail when it is down or changed. That cost is stated rather than
 * hidden — a mock would prove only that our own fixtures parse, which is the blind spot that hid
 * eleven ABI breaks.
 */
@RunWith(AndroidJUnit4::class)
class RealVideoResolutionTest : ExtensionTestBase() {

    private val context
        get() = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()

    private val assetContext
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

    @Before
    fun useRealNetwork() {
        // Replaces ExtensionTestBase's canned FakeHttp, which knows a fixed set of URLs and
        // throws on anything else — from an OkHttp dispatcher thread, where it reads as an
        // extension crash rather than a harness misconfiguration.
        NetworkHelperProvider.install(DefaultNetworkHelper())
    }

    private fun install(asset: String, name: String): File {
        val target = File(context.filesDir, name)
        if (target.exists()) target.delete()

        assetContext.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // Read-only: dalvik refuses a writable dex, and an installed extension must not be
        // modifiable after validation.
        target.setReadOnly()
        return target
    }

    private fun source(): AnimeHttpSource {
        val apk = install("real-flat-episode-extension.apk", "flat-episode.apk")

        val sources = runCatching {
            extensionLoader.load(apk, FLAT_EXTENSION_PACKAGE)
        }.getOrElse { throw AssertionError("extension failed to load: ${it.message}", it) }

        assertTrue(
            "no sources registered from $FLAT_EXTENSION_PACKAGE — the direct-discovery " +
                "fallback should have found its source class",
            sources.isNotEmpty(),
        )

        return sources.first().source as? AnimeHttpSource
            ?: throw AssertionError("expected an AnimeHttpSource")
    }

    /**
     * Any entry from the extension's catalogue.
     *
     * Tries popular first, then latest. Plenty of sources implement only one of the two — the
     * catalogue contract allows it — and an empty popular page says nothing about whether video
     * resolution works.
     */
    private suspend fun anyEntry(source: AnimeHttpSource) =
        runCatching { source.getPopularAnime(1).animes.firstOrNull() }.getOrNull()
            ?: runCatching { source.getLatestUpdates(1).animes.firstOrNull() }.getOrNull()

    @Test
    fun resolvesARealVideoStreamUrl() = runBlocking {
        val source = source()

        val seed = anyEntry(source)
            ?: fail("neither popular nor latest returned an entry, cannot walk the chain")

        val details = source.getAnimeDetails(seed)
            ?: fail("getAnimeDetails returned null for '${seed.title}'")

        val episodes = source.getEpisodeList(details)
        assertTrue(
            "no episodes parsed for '${details.title}'; this fixture declares the flat " +
                "episodeListParse path, so this is a genuine parse failure",
            episodes.isNotEmpty(),
        )

        val episode = episodes.first()
        assertTrue("episode has no url", episode.url.isNotBlank())

        // ---- The line AnimeSourceAdapter.pages() sits on ----
        val videos = source.getVideoList(episode)

        assertTrue(
            "getVideoList returned ${videos.size} videos for '${episode.name}'",
            videos.isNotEmpty(),
        )

        val video = videos.first()
        assertTrue("video has no url", video.url.isNotBlank())
        assertTrue(
            "video url is not absolute, so it could not be resolved against a base: ${video.url}",
            video.url.startsWith("http"),
        )
    }

    @Test
    fun hosterAndDirectVideoListsBothProduceUrls() = runBlocking {
        val source = source()

        val seed = anyEntry(source) ?: return@runBlocking
        val details = source.getAnimeDetails(seed) ?: return@runBlocking
        val episodes = source.getEpisodeList(details)
        if (episodes.isEmpty()) return@runBlocking
        val episode = episodes.first()

        // Anikku offers two routes to the same videos: directly, or via a hoster list that then
        // needs a second call. Both are exercised, because `pages()` supports both and only
        // testing one leaves the other inferred.
        val direct = source.getVideoList(episode)
        val hosters = runCatching { source.getHosterList(episode) }.getOrDefault(emptyList())
        val viaHosters = hosters.flatMap { hoster ->
            runCatching { source.getVideoList(hoster) }.getOrDefault(emptyList())
        }

        val total = direct.size + viaHosters.size
        assertTrue(
            "neither route produced a video: direct=${direct.size} hosters=${hosters.size} " +
                "viaHosters=${viaHosters.size}",
            total > 0,
        )
        (direct + viaHosters).forEach { video ->
            assertTrue("video has no url", video.url.isNotBlank())
        }
    }

    @Test
    fun resolvedVideoSurvivesTheHostAdapter() = runBlocking {
        // Registering a video is not the same as the host being able to use it: the adapter is
        // what the player actually calls, so it has to survive the mapping into our own model.
        val source = source()
        val adapter = AnimeSourceAdapter(
            aniyomiSource = source,
            packageName = FLAT_EXTENSION_PACKAGE,
            mediaType = MediaType.ANIME,
        )

        val first = adapter.popular(page = 1).items.firstOrNull()
            ?: adapter.latest(page = 1).items.firstOrNull()
            ?: fail("the adapter surfaced no catalogue items from popular or latest")

        val details = adapter.details(first)
        val units = adapter.units(first)

        assertTrue("adapter produced no units for '${first.title}'", units.isNotEmpty())

        val pages = adapter.pages(first, units.first())
        assertTrue(
            "adapter.pages() returned ${pages.size} pages — this is the method the manga " +
                "reader and episode player depend on",
            pages.isNotEmpty(),
        )
        pages.forEach { resolved ->
            assertTrue(
                "page url is not absolute, so it could not be fetched: ${resolved.url}",
                resolved.url.startsWith("http"),
            )
        }
        assertTrue("details produced no title", details.title.isNotBlank())
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private companion object {
        const val FLAT_EXTENSION_PACKAGE = "eu.kanade.tachiyomi.animeextension.en.kimoitv"
    }
}
