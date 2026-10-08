package com.auris.app.source

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dalvik.system.DexClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory
import java.io.File
import java.lang.reflect.Modifier

/**
 * Loads extension APKs and reflects their sources out.
 *
 * ### How it works
 * An extension is a separate APK containing only its own classes; everything else it needs —
 * `eu.kanade.tachiyomi.*`, OkHttp, Jsoup — is expected to already be in the host. Loading is
 * therefore a [DexClassLoader] whose *parent* is the app's own loader. That parent-first
 * delegation is what makes the compatibility layer work: a class named
 * `eu.kanade.tachiyomi.animesource.model.SAnime` resolves to the host's copy, so the
 * extension's `SAnime` and the host's `SAnime` are the same type and can cross the boundary
 * without reflection.
 *
 * The flip side is deliberate: an extension cannot shadow an API class. If a third-party APK
 * bundled its own `SAnime`, the host's would win. That prevents an extension from redefining
 * internals, at the cost of requiring extensions to be built against a compatible API.
 *
 * ### Cache directory
 * Optimized dex is written to a per-extension subdirectory. Each extension needs its own
 * `dexOutputDir`; sharing one across loaders is a known source of `ClassNotFoundException`
 * when two APKs contain overlapping class names.
 *
 * ### Lifecycle
 * Loaders are held for the process lifetime (see [unloadAll]). Closing a loader does not
 * unload classes that have already been referenced, so dropping a loader while a screen still
 * holds a source leaves classes that cannot be instantiated again. Unloading is therefore
 * explicit, and happens at app teardown rather than on "uninstall".
 */
class ExtensionLoader(
    private val context: Context,
    private val cacheRoot: File = File(context.cacheDir, CACHE_DIR),
    /**
     * Parent for loaded classes. Defaults to the app class loader, which is what makes
     * `eu.kanade.tachiyomi.*` resolve to the host's compatibility layer.
     */
    private val parentClassLoader: ClassLoader = requireNotNull(ExtensionLoader::class.java.classLoader),
) : ExtensionSourceRegistry {

    private val loaded = mutableMapOf<String, LoadedExtension>()

    /**
     * Every source across all loaded extensions.
     *
     * One flat list: Anikku unifies anime and manga into a single `AnimeSource` hierarchy
     * (see [LoadedSource]), so there is nothing to partition on.
     */
    override val sources: List<LoadedSource>
        get() = loaded.values.flatMap { it.allSources }

    @VisibleForTesting
    internal val loadedPackages: Set<String>
        get() = loaded.keys.toSet()

    /**
     * Loads one extension APK and reflects its sources.
     *
     * Failure is contained to the one APK: a broken extension must not stop the others from
     * working, so errors are logged and that extension contributes no sources.
     *
     * @return the sources found, or an empty list if the APK yielded none
     */
    fun load(apkFile: File, packageName: String): List<LoadedSource> {
        loaded[packageName]?.let { existing ->
            if (existing.apkPath == apkFile.absolutePath) return existing.allSources
        }

        return try {
            val loader = createClassLoader(apkFile, packageName)
            val discovered = discoverSources(loader, apkFile)

            loaded[packageName] = LoadedExtension(
                apkPath = apkFile.absolutePath,
                classLoader = loader,
                allSources = discovered.toLoadedSources(packageName),
            )

            Log.i(
                TAG,
                "Loaded $packageName: ${discovered.animeSources.size} anime, " +
                    "${discovered.mangaSources.size} manga",
            )
            discovered.toLoadedSources(packageName)
        } catch (e: Throwable) {
            // Catching Throwable is right here: an extension built against a different
            // extension-lib version fails as ClassCastException, NoClassDefFoundError or
            // VerifyError, none of which are Exception.
            Log.e(TAG, "Failed to load extension $packageName", e)
            emptyList()
        }
    }

    /** Loads many extensions, keeping the ones that work. */
    fun loadAll(apks: List<File>): Map<String, List<LoadedSource>> {
        val result = mutableMapOf<String, List<LoadedSource>>()
        for (apk in apks) {
            val packageName = runCatching {
                ExtensionApkReader.read(context, apk).packageName
            }.getOrNull() ?: continue

            result[packageName] = load(apk, packageName)
        }
        return result
    }

    /**
     * Finds every `AnimeSourceFactory` / `SourceFactory` implementation in the APK and asks
     * each for its sources.
     *
     * Discovery is by interface rather than by the `@ExtensionSourceFactories` annotation, so
     * a source set works without Aniyomi's annotations being present, and so a factory nested
     * in a Kotlin `object` (how Aniyomi's own extensions are written) is still found.
     */
    private fun discoverSources(loader: DexClassLoader, apkFile: File): DiscoveredSources {
        val classNames = runCatching {
            enumerateClasses(apkFile)
        }.getOrElse { e ->
            Log.e(TAG, "Could not enumerate classes in ${apkFile.name}", e)
            emptyList()
        }

        val animeSources = mutableListOf<AnimeSource>()
        val mangaSources = mutableListOf<AnimeSource>()

        for (className in classNames) {
            when (val instance = instantiateFactory(className, loader)) {
                is AnimeSourceFactory -> animeSources += createSources(instance, className)
                // `SourceFactory.createSources()` returns `List<CatalogueSource>`, which under
                // Anikku is a typealias of `List<AnimeSource>` — same type, so this branch
                // simply appends to the same pool.
                is SourceFactory -> mangaSources += createSources(instance, className)
            }
        }

        return DiscoveredSources(animeSources, mangaSources)
    }

    private fun createSources(factory: Any, className: String): List<AnimeSource> = runCatching {
        when (factory) {
            is AnimeSourceFactory -> factory.createSources()
            is SourceFactory -> factory.createSources()
            else -> emptyList()
        }
    }.onFailure {
        Log.e(TAG, "Factory $className threw from createSources()", it)
    }.getOrDefault(emptyList())

    /**
     * Instantiates [className] if it is a factory with an accessible no-arg constructor.
     *
     * Returns null rather than throwing for the non-factory classes, which are the
     * overwhelming majority of any APK.
     */
    private fun instantiateFactory(className: String, loader: DexClassLoader): Any? {
        val clazz = try {
            loader.loadClass(className)
        } catch (e: Throwable) {
            // NoClassDefFoundError for a source whose transitive deps are missing.
            return null
        }

        if (!isFactory(clazz)) return null

        return try {
            val constructor = clazz.getDeclaredConstructor()
            // Aniyomi factories are top-level classes, but allow non-public too, for Kotlin
            // `internal` objects.
            if (!constructor.isAccessible) constructor.isAccessible = true
            constructor.newInstance()
        } catch (e: Throwable) {
            Log.w(TAG, "Factory $className is not instantiable: ${e.message}")
            null
        }
    }

    private fun isFactory(clazz: Class<*>): Boolean {
        if (clazz.isInterface || Modifier.isAbstract(clazz.modifiers)) return false
        return AnimeSourceFactory::class.java.isAssignableFrom(clazz) ||
            SourceFactory::class.java.isAssignableFrom(clazz)
    }

    /**
     * Class names defined by the APK.
     *
     * There is no public API for "list the classes in this APK": `DexClassLoader` exposes no
     * listing, and reflection needs a name to look up in the first place. So the dex entries
     * are read directly for their type descriptors — the same information `DexPathList` holds
     * internally, without depending on hidden APIs.
     *
     * Falls back to reflectively probing the dex output directory when the APK cannot be read
     * directly, so a loader given a bare `.dex` still works.
     */
    private fun enumerateClasses(apkFile: File): List<String> {
        // A bare `.dex` input is read directly; an APK/zip is read through the archive.
        if (apkFile.isFile && apkFile.name.endsWith(".dex")) {
            return runCatching { DexFileReader(apkFile.absolutePath).readClassNames() }
                .onFailure { Log.e(TAG, "Could not read ${apkFile.name}", it) }
                .getOrDefault(emptyList())
        }

        return runCatching { DexFileReader(apkFile.absolutePath).readClassNamesFromArchive() }
            .onFailure { Log.e(TAG, "Could not read classes in ${apkFile.name}", it) }
            .getOrDefault(emptyList())
    }


    /**
     * A classloader for [apkPath] without registering anything.
     *
     * Exists so tests can ask the dalvik VM directly whether a type resolves. `load` catches
     * `Throwable` and returns an empty list, which makes "the extension has no sources" and
     * "the extension could not be linked" indistinguishable — and that ambiguity is precisely
     * how a missing API type went unnoticed while every test stayed green.
     */
    fun classLoaderFor(apkPath: String): ClassLoader {
        val apk = File(apkPath)
        return loaded.entries.firstOrNull { it.value.apkPath == apkPath }?.value?.classLoader
            ?: createClassLoader(apk, apk.nameWithoutExtension)
    }

    private fun createClassLoader(apkFile: File, packageName: String): DexClassLoader {
        // Dalvik refuses a writable dex/jar outright:
        //   SecurityException: Writable dex file '...' is not allowed.
        // An installed extension must therefore be read-only, and making it so here means a
        // caller cannot trip this by copying an APK out of assets or a download without
        // thinking about permissions.
        ensureReadOnly(apkFile)

        // Per-extension output dir; see the class doc for why these cannot be shared.
        val outputDir = File(cacheRoot, packageName).apply { mkdirs() }

        return DexClassLoader(
            apkFile.absolutePath,
            outputDir.absolutePath,
            null,
            parentClassLoader,
        )
    }

    /** Clears the write bit, and reports rather than throws if the file system disagrees. */
    private fun ensureReadOnly(file: File) {
        if (!file.canWrite()) return
        runCatching { file.setReadOnly() }
        if (file.canWrite()) {
            Log.w(
                TAG,
                "Could not mark ${file.name} read-only; DexClassLoader may reject it. " +
                    "On most Android versions the app owns this file so setReadOnly succeeds.",
            )
        }
    }

    /**
     * Forgets every loader and drops the optimized dex on disk.
     *
     * There is deliberately **no** `BaseDexClassLoader.close()` call: it is not part of the
     * public API (`android.jar` does not declare it), so calling it would need reflection or
     * a hidden-API exemption. Dropping the references lets the loaders be collected with the
     * process, which is all that "unload" can honestly mean — see the lifecycle note on the
     * class doc for why this is only safe at teardown.
     */
    fun unloadAll() {
        val count = loaded.size
        loaded.clear()
        clearCache()
        Log.i(TAG, "Unloaded $count extension(s) and cleared dex cache")
    }

    /** Removes optimized dex so the next load re-extracts from the APK. */
    fun clearCache() {
        cacheRoot.deleteRecursively()
    }

    private data class DiscoveredSources(
        val animeSources: List<AnimeSource>,
        val mangaSources: List<AnimeSource>,
    ) {
        fun toLoadedSources(packageName: String): List<LoadedSource> =
            (animeSources + mangaSources).map { LoadedSource(it, packageName) }
    }

    private class LoadedExtension(
        val apkPath: String,
        val classLoader: DexClassLoader,
        val allSources: List<LoadedSource>,
    )

    companion object {
        private const val TAG = "AurisExtensions"
        internal const val CACHE_DIR = "extensions"
    }
}