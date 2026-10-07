package com.auris.app.source

/**
 * Read access to the sources currently loaded from extensions.
 *
 * ### Why this exists
 * [ExtensionLoader] loads APKs through `DexClassLoader` and owns a private registry, so it
 * cannot be substituted in a unit test without one. Everything that needs to find the source
 * behind a library entry depends on this instead.
 *
 * A narrow interface is also the honest shape of the dependency: consumers need to *look up* a
 * source, not load an APK. Keeping [ExtensionLoader.load] off this type means a future caller
 * cannot accidentally trigger extension loading from a read-only path.
 */
interface ExtensionSourceRegistry {

    /**
     * Every source across all loaded extensions.
     *
     * Empty when no extension is installed — an expected state, not an error. Under Anikku
     * anime and manga are one hierarchy, so this is a single flat list with nothing to
     * partition on.
     */
    val sources: List<LoadedSource>
}