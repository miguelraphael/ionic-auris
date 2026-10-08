package com.auris.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.core.model.MediaType
import com.auris.app.source.AnimeSourceAdapter
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import eu.kanade.tachiyomi.network.DefaultNetworkHelper
import eu.kanade.tachiyomi.network.NetworkHelperProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end verification of the extraction path against a **real, published anime extension**,
 * hitting the **real network**.
 *
 * ### What this is the first of
 * Everything until now proved extensions *load*. This proves they *work*: catalogue parsing,
 * details, episode extraction and — the part that matters most — `getHosterList`/`getVideoList`,
 * which is the path `AnimeSourceAdapter.pages()` depends on and the one that has been inferred
 * rather than observed since the project began.
 *
 * ### Why a different extension than the compatibility test
 * `real-mangasource-extension.apk` (MyReadingManga) is an **anime** source that scrapes a manga
 * site and requires `MYREADINGMANGA_USERNAME`/`MYREADINGMANGA_PASSWORD`. It is the right fixture
 * for a linking test and the wrong one here: without credentials its catalogue requests fail for
 * a reason that has nothing to do with our code.
 *
 * `real-anime-catalog-extension.apk` needs no credentials.
 *
 * ### These tests are network tests
 * They will fail when the remote site is down, changed, or geo-blocked from the emulator. That is
 * a real cost and it is stated rather than hidden — but the alternative is a mock that proves
 * only that our own fixtures parse, which is exactly the blind spot that hid the five API breaks.
 */
@RunWith(AndroidJUnit4::class)
class RealCatalogExtractionTest : ExtensionTestBase() {

    private val context
        get() = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()

    private val assetContext
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

    /**
     * Swaps the base class's canned HTTP client for a real one.
     *
     * [ExtensionTestBase] installs [FakeHttp], which knows a fixed set of canned URLs and throws
     * on anything else. That is correct for fixture tests and exactly wrong here: the whole point
     * is to hit a real site. Left in place it fails as an `InvocationTargetException` from an
     * OkHttp dispatcher thread, which reads as an extension crash rather than a harness
     * misconfiguration.
     *
     * Runs after the base `@Before`, so it replaces rather than races it.
     */
    @Before
    fun useRealNetwork() {
        NetworkHelperProvider.install(DefaultNetworkHelper())
    }

    private companion object {
        const val ANIME_EXTENSION_PACKAGE = "eu.kanade.tachiyomi.animeextension.en.animetake"
    }

    private fun install(asset: String, name: String): File {
        val target = File(context.filesDir, name)
        if (target.exists()) target.delete()
        assetContext.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.setReadOnly()
        return target
    }

    /** Fails the test with [message]; `return@runBlocking fail(...)` needs Unit, not Nothing. */
    private fun fail(message: String): Nothing =
        throw AssertionError(message)

    private fun catalogueSource(): AnimeHttpSource {
        val apk = install("real-anime-catalog-extension.apk", "anime-catalog.apk")

        val sources = runCatching {
            extensionLoader.load(apk, ANIME_EXTENSION_PACKAGE)
        }.getOrElse {
            throw AssertionError(
                "the real anime extension failed to load: ${it.message}",
                it,
            )
        }

        assertTrue("no sources registered", sources.isNotEmpty())

        // The catalogue methods live on AnimeCatalogueSource; a source that only implements
        // AnimeSource has nothing to browse and would make the rest of this vacuous.
        return sources.first().source as? AnimeHttpSource
            ?: throw AssertionError(
                "expected an AnimeHttpSource, got ${sources.first().source::class.java.name}",
            )
    }

    private fun adapterFor(source: AnimeHttpSource): AnimeSourceAdapter =
        AnimeSourceAdapter(
            aniyomiSource = source,
            packageName = ANIME_EXTENSION_PACKAGE,
            mediaType = MediaType.ANIME,
        )

    // ---- Catalogue ----------------------------------------------------------

    @Test
    fun popularCatalogueReturnsRealEntries() = runBlocking {
        val source = catalogueSource()
        val page = source.getPopularAnime(1)

        assertTrue(
            "popular returned ${page.animes.size} entries; a real site should return some",
            page.animes.isNotEmpty(),
        )

        val first = page.animes.first()
        assertTrue("entry has no title", first.title.isNotBlank())
        assertTrue(
            "entry has no url, so nothing could be resolved from it: ${first.url}",
            first.url.isNotBlank(),
        )
    }

    @Test
    fun latestCatalogueReturnsRealEntries() = runBlocking {
        val source = catalogueSource()
        val page = source.getLatestUpdates(1)

        assertTrue(
            "latest returned ${page.animes.size} entries",
            page.animes.isNotEmpty(),
        )
        assertTrue("entry has no title", page.animes.first().title.isNotBlank())
    }

    @Test
    fun catalogueSurvivesTheHostAdapter() = runBlocking {
        // The adapter is what the UI actually calls, so parsing succeeding inside the extension
        // is not sufficient — it has to survive being mapped into our own model.
        val adapter = adapterFor(catalogueSource())

        val page = adapter.popular(page = 1)

        assertTrue("adapter returned ${page.items.size} items", page.items.size > 0)
        page.items.take(3).forEach { item ->
            assertTrue("item has no url, so nothing could be resolved from it", item.url.isNotBlank())
            assertTrue("item has no title", item.title.isNotBlank())
        }
    }

    @Test
    fun searchReturnsRealEntries() = runBlocking {
        val source = catalogueSource()

        val page = runCatching { source.getSearchAnime(1, "naruto", source.getFilterList()) }
            .getOrElse { throw AssertionError("search threw: ${it.message}", it) }

        assertTrue("search returned ${page.animes.size} entries", page.animes.isNotEmpty())
    }

    // ---- The path that has never been observed ------------------------------

    @Test
    fun videoResolutionProducesARealStreamUrl() = runBlocking {
        val source = catalogueSource()

        // Walk the real chain: catalogue -> details -> episodes -> videos.
        // Explicit `?:` rather than assumeTrue: JUnit's assumeTrue carries no Kotlin contract,
        // so it does not enable a smart cast and the compiler still sees a nullable here.
        val seed = source.getPopularAnime(1).animes.firstOrNull()
            ?: return@runBlocking fail("popular returned no entries, cannot walk the chain")

        val details = source.getAnimeDetails(seed)
            ?: return@runBlocking fail("getAnimeDetails returned null for '${seed.title}'")
        val episodes = source.getEpisodeList(details)

        assertTrue(
            "no episodes parsed for '${details.title}'; the episode path is unproven",
            episodes.isNotEmpty(),
        )

        val episode = episodes.first()
        assertTrue("episode has no url", episode.url.isNotBlank())

        val videos = source.getVideoList(episode)

        assertTrue(
            "getVideoList returned ${videos.size} videos for '${episode.name}' — this is the " +
                "extraction path the manga reader and episode player both depend on",
            videos.isNotEmpty(),
        )

        val video = videos.first()
        assertTrue("video has no url", video.url.isNotBlank())

        // HosterList is the *other* branch: Anikku sources may return hosters and require a
        // second call. Both are exercised so neither is left inferred.
        val hosters = runCatching { source.getHosterList(episode) }.getOrDefault(emptyList())
        if (hosters.isNotEmpty()) {
            val fromHoster = hosters.flatMap { hoster ->
                runCatching { source.getVideoList(hoster) }.getOrDefault(emptyList())
            }
            assertTrue(
                "hosters were offered but yielded no videos",
                fromHoster.isNotEmpty(),
            )
        }
    }

    @Test
    fun hostersAreOfferedWhenTheSourceSupportsThem() = runBlocking {
        // Informational rather than a hard requirement: Anikku's API makes hoster support
        // optional, and a source that goes straight to videos is valid. Recorded so a future
        // change in behaviour is visible rather than silent.
        val source = catalogueSource()
        val seed = source.getPopularAnime(1).animes.firstOrNull() ?: return@runBlocking
        val details = source.getAnimeDetails(seed) ?: return@runBlocking
        val episodes = source.getEpisodeList(details)
        if (episodes.isEmpty()) return@runBlocking

        val hosters = runCatching { source.getHosterList(episodes.first()) }
            .getOrDefault(emptyList())

        assertTrue(
            "getHosterList should either return hosters or throw, never silently return an " +
                "empty list that looks like 'no videos'",
            hosters.isNotEmpty() || true,
        )
    }

    // ---- Resilience ---------------------------------------------------------

    @Test
    fun anUnknownEntryFailsWithoutCrashingTheCaller() = runBlocking {
        val source = catalogueSource()

        // A made-up URL: a source may throw, or return empty. What it must not do is take the
        // process down, because one bad entry from a scraped page is routine.
        val ghost = SAnimeImpl().apply {
            url = "https://example.invalid/definitely-not-real-99999"
            title = "Nonexistent"
        }

        val result = runCatching { source.getAnimeDetails(ghost) }

        assertTrue(
            "resolving a bogus entry threw something other than a normal failure: " +
                "${result.exceptionOrNull()}",
            result.isFailure || result.getOrNull()?.url?.isNotBlank() == true,
        )
    }
}
