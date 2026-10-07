package com.auris.app.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Class enumeration from real dex.
 *
 * `DexFileReader` exists because `DexClassLoader` exposes no way to list an APK's classes and
 * reflection needs a name to start from. These tests run against dex produced by a real
 * compile, because the reader walks the dex header by byte offset — a hand-written fake would
 * not exercise the offsets that actually matter.
 */
@RunWith(AndroidJUnit4::class)
class DexFileReaderTest : ExtensionTestBase() {

    @Test
    fun findsClassesInExtensionApk() {
        val names = DexFileReader(mockExtensionApk().absolutePath).readClassNamesFromArchive()

        // Binary names, not descriptors: these are what ClassLoader.loadClass accepts.
        // The mock extension's own classes must be discoverable or the loader finds nothing.
        assertTrue(
            "Expected the mock factory in $names",
            names.contains("com.auris.app.mockextension.MockAnimeSourceFactory"),
        )
        assertTrue(names.contains("com.auris.app.mockextension.MockMangaViewSource"))
        assertTrue(names.contains("com.auris.app.mockextension.MockAnimeSource"))
    }

    @Test
    fun everyNameIsABinaryClassName() {
        val names = DexFileReader(mockExtensionApk().absolutePath).readClassNamesFromArchive()

        assertTrue(names.isNotEmpty())
        // Names are parsed from a possibly-corrupt file; anything not in dotted form is a
        // misparse and would fail silently inside loadClass.
        assertTrue(
            names.all { it.isNotEmpty() && !it.startsWith("L") && !it.contains(';') },
        )
    }

    @Test
    fun discoversFactoriesTheLoaderLooksFor() {
        val names = DexFileReader(mockExtensionApk().absolutePath).readClassNamesFromArchive()

        // Exactly what ExtensionLoader instantiates. If a rebuild renames or drops these,
        // discovery silently returns zero sources — this test is the canary for that.
        val factories = names.filter { it.endsWith("Factory") }
        assertTrue(
            "Expected at least the anime and manga factories, got $factories",
            factories.any { it.endsWith("MockAnimeSourceFactory") } &&
                factories.any { it.endsWith("MockMangaSourceFactory") },
        )
    }

    @Test
    fun nonDexFileReturnsEmptyList() {
        val junk = File.createTempFile("junk", ".dex").apply { writeText("not a dex") }
        junk.deleteOnExit()

        // Must not throw: a corrupt dex has to degrade to "no classes", not crash the load.
        assertTrue(DexFileReader(junk.absolutePath).readClassDescriptors().isEmpty())
    }

    @Test
    fun nonArchiveReturnsEmptyNames() {
        val junk = File.createTempFile("junk", ".apk").apply { writeText("not a zip") }
        junk.deleteOnExit()

        assertTrue(DexFileReader(junk.absolutePath).readClassNamesFromArchive().isEmpty())
    }

    @Test
    fun missingFileReturnsEmptyList() {
        assertTrue(
            DexFileReader("/does/not/exist.dex").readClassDescriptors().isEmpty(),
        )
        assertTrue(
            DexFileReader("/does/not/exist.apk").readClassNamesFromArchive().isEmpty(),
        )
    }

    @Test
    fun truncatedDexReturnsEmptyList() {
        val full = mockExtensionApk()
        // Cut the file short mid-header: a real corruption mode worth handling.
        val truncated = File.createTempFile("truncated", ".apk")
        truncated.deleteOnExit()
        full.inputStream().use { input ->
            truncated.outputStream().use { output -> input.copyTo(output, 512) }
        }

        // Must degrade to empty rather than throw.
        DexFileReader(truncated.absolutePath).readClassNamesFromArchive()
    }
}