package com.auris.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Evidence about **real, published extensions**, gathered against the app's own loader.
 *
 * ### The finding this file records
 * The Aniyomi anime extension commits to the Android SDK. It loads without throwing, and then
 * registers **zero sources**, because the dex references three types that `:source-api` does not
 * provide:
 *
 * ```
 * NoClassDefFoundError: Failed resolution of:
 *   Leu/kanade/tachiyomi/animesource/ConfigurableAnimeSource;
 * Caused by: ClassNotFoundException: Didn't find class
 *   "eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource"
 * ```
 *
 * plus `eu.kanade.tachiyomi.animesource.model.AnimeFilter` and `AnimeFilterList`. Anikku has an
 * **animesource-namespaced** filter API; we implemented the Mihon-namespaced `source.model.Filter`
 * and `FilterList` instead. `ConfigurableAnimeSource` is absent entirely.
 *
 * ### Why the mock extension never caught this
 * `mock-extension` is written against our own interfaces, so it always matches by construction.
 * Every "real APK" claim in this project until now was therefore self-referential. The
 * `:source-api-conformance` guard passes green because it checks our own declaration against our
 * own expectation — it has no external input and so cannot detect that the API is *incomplete*.
 *
 * That is the exact failure the conformance guard was meant to prevent, and it is worth stating
 * plainly: **a green build did not mean extensions worked.**
 *
 * ### How these tests are written
 * `ExtensionLoader.load` catches `Throwable` and returns an empty list, so "did it load" cannot be
 * read from the return value — an unloadable extension and an extension with no sources are
 * indistinguishable. These tests therefore assert against the classloader directly, which is
 * deterministic. When the three missing types are added, [apiTypesReferencedByRealExtensionsAreResolvable]
 * fails and names exactly what to do next.
 */
@RunWith(AndroidJUnit4::class)
class RealExtensionCompatibilityTest : ExtensionTestBase() {

    private val context
        get() = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()

    private val assetContext
        get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context

    private fun install(asset: String, name: String): File {
        val target = File(context.filesDir, name)
        if (target.exists()) target.delete()

        assetContext.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // Read-only, as an installed extension is: a writable dex can be replaced after
        // DexClassLoader validated it, and dalvik refuses a writable dex outright.
        target.setReadOnly()
        return target
    }

    private fun loaderFor(apk: File, packageName: String) = runCatching {
        extensionLoader.load(apk, packageName)
    }

    // ---- What a real extension proves --------------------------------------

    @Test
    fun theAniyomiExtensionIsReadByOurHostApiPackage() {
        // The good news, and the reason the rest of this is a fixable gap rather than a wrong
        // architecture: a real extension is compiled against `eu.kanade.tachiyomi.animesource.*`,
        // which is the package `:source-api` provides. The typealias-merge invariant holds.
        val apk = install("real-mangasource-extension.apk", "aniyomi-anime.apk")

        loaderFor(apk, "eu.kanade.tachiyomi.animeextension.all.myreadingmanga")

        val resolved = runCatching {
            extensionLoader.classLoaderFor(apk.absolutePath)
                .loadClass("eu.kanade.tachiyomi.animesource.AnimeSource")
        }

        assertTrue(
            "the animesource package our API provides is not visible to the extension's " +
                "classloader, so the typealias-merge invariant is broken",
            resolved.isSuccess,
        )
    }

    @Test
    fun apiTypesReferencedByRealExtensionsAreResolvable() {
        val apk = install("real-mangasource-extension.apk", "aniyomi-anime.apk")

        loaderFor(apk, "eu.kanade.tachiyomi.animeextension.all.myreadingmanga")
        val loader = extensionLoader.classLoaderFor(apk.absolutePath)

        val missing = REAL_EXTENSION_API_TYPES.filterNot { type ->
            runCatching { loader.loadClass(type) }.isSuccess
        }

        assertTrue(
            "`:source-api` is missing types that real extensions reference: $missing. " +
                "Until these exist, no real extension can register a source.",
            missing.isEmpty(),
        )
    }

    @Test
    fun aRealExtensionRegistersItsSources() {
        // The end-to-end proof. Every earlier assertion checks one link in the chain; this one
        // proves the whole chain holds for an extension this project did not write.
        //
        // Getting here required, in order: real `AnimeFilter`/`AnimeFilterList` classes (a
        // `typealias` emits no JVM class), `ConfigurableAnimeSource`, the Injekt dependency, an
        // Injekt registration of the Application, `ParsedAnimeHttpSource.network`, and
        // `NetworkHelper` as an abstract class rather than an interface. Each of those failures
        // presented identically — loads cleanly, registers zero sources.
        val apk = install("real-mangasource-extension.apk", "aniyomi-anime.apk")

        val sources = loaderFor(apk, "eu.kanade.tachiyomi.animeextension.all.myreadingmanga")
            .getOrDefault(emptyList())

        assertTrue(
            "a real Aniyomi extension registered no sources; see the loader log for the link " +
                "that failed",
            sources.isNotEmpty(),
        )
    }

    @Test
    fun discoveredSourcesAreUsableHostObjects() {
        // Registering is not the same as working: assert the host can actually treat what came
        // back, so a source that loads but cannot be adapted is caught here.
        val apk = install("real-mangasource-extension.apk", "aniyomi-anime.apk")

        val sources = loaderFor(apk, "eu.kanade.tachiyomi.animeextension.all.myreadingmanga")
            .getOrDefault(emptyList())

        assertTrue("no sources discovered", sources.isNotEmpty())
        sources.forEach { loaded ->
            assertTrue(
                "source has no id, so it cannot be persisted",
                loaded.source.id != 0L,
            )
            assertTrue(
                "source has no name, so it cannot be shown",
                loaded.source.name.isNotBlank(),
            )
            assertTrue(
                "source has no language",
                loaded.source.lang.isNotBlank(),
            )
        }
    }

    // ---- Why the manga question has a different answer -----------------------

    @Test
    fun aKeiyoushiMangaExtensionTargetsADifferentApiGenerationEntirely() {
        // The Keiyoushi/Mihon manga extensions are a *separate hierarchy*, not a gap in ours:
        //
        //   Anikku   : eu.kanade.tachiyomi.animesource.*  + getHosterList/getVideoList
        //   Mihon    : eu.kanade.tachiyomi.source.*       + fetchPageList
        //
        // The manifest of `extension.en.aquamanga` references `source/online/HttpSource` and
        // `source/model/{SManga,SChapter,Page,MangasPage}` — none of which `:source-api`
        // provides, and none of which Anikku-style manga code could be adapted onto without a
        // second source API. So pointing the repository fetcher at Keiyoushi cannot make
        // `AnimeSourceAdapter.pages()` succeed; the extensions cannot load at all.
        val apk = install("real-keiyoushi-manga-extension.apk", "keiyoushi-manga.apk")

        loaderFor(apk, "eu.kanade.tachiyomi.extension.en.aquamanga")

        val mihonBase = runCatching {
            extensionLoader.classLoaderFor(apk.absolutePath)
                .loadClass("eu.kanade.tachiyomi.source.online.HttpSource")
        }

        // Its own base class is absent from our API, which is the whole story in one assertion.
        assertTrue(
            "Mihon's `source.online.HttpSource` unexpectedly resolved; if `:source-api` now " +
                "provides it, the two hierarchies have been reconciled and the manga blocker " +
                "in the README is stale",
            mihonBase.isFailure,
        )
    }

    private companion object {
        /** Referenced by every real Aniyomi/Anikku extension; absent from `:source-api`. */
        val REAL_EXTENSION_API_TYPES = listOf(
            "eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource",
            "eu.kanade.tachiyomi.animesource.model.AnimeFilter",
            "eu.kanade.tachiyomi.animesource.model.AnimeFilterList",
        )
    }
}
