package com.auris.app.source

import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.ExtensionEntity
import com.auris.app.data.repository.ExtensionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Extension bookkeeping that needs no device: APK identity parsing and the
 * install/uninstall lifecycle.
 *
 * The loading mechanism is covered separately in `ExtensionLoaderTest`, which needs a real
 * dalvik VM.
 */
class ExtensionRepositoryTest {

    private val dao: com.auris.app.data.local.ExtensionDao = FakeExtensionDao()

    private val repository = ExtensionRepository(dao)

    /**
     * [ExtensionRepository.installed] returns a cold Flow backed by a DAO. The fake emits a
     * snapshot of its current map, so collecting it once yields the state at that moment.
     */
    private suspend fun installed() = repository.installed().first()

    private suspend fun enabled() = repository.enabled().first()

    // ---- Identity -----------------------------------------------------------

    @Test
    fun registersExtensionFromMetadata() = runBlocking {
        val entity = ExtensionEntity(
            packageName = "eu.kanade.tachiyomi.animeextension.en.example",
            label = "Example",
            versionName = "1.4.2",
            versionCode = 14,
            apkPath = "/data/extensions/example.apk",
            signatureHash = "abc123",
        )

        repository.register(entity)

        assertEquals(1, installed().size)
        assertEquals("1.4.2", installed().single().versionName)
        assertEquals(14L, installed().single().versionCode)
    }

    @Test
    fun reinstallingSamePackageReplacesRow() = runBlocking {
        repository.register(entity(versionCode = 1, versionName = "1.0"))
        repository.register(entity(versionCode = 2, versionName = "1.1"))

        val installed = installed()
        // One row per package: a second APK for the same extension is an update, not a
        // duplicate, or the extension list would show two entries.
        assertEquals(1, installed.size)
        assertEquals(2L, installed.single().versionCode)
        assertEquals("1.1", installed.single().versionName)
    }

    @Test
    fun uninstallRemovesExtensionAndItsApk() = runBlocking {
        val apk = java.io.File.createTempFile("ext", ".apk").apply { writeText("x") }
        repository.register(entity(apkPath = apk.absolutePath))

        repository.uninstall("eu.kanade.tachiyomi.animeextension.en.example")

        assertTrue(installed().isEmpty())
        // The APK file itself must go too, or reinstalling leaves stale versions behind.
        assertTrue("APK should be deleted on uninstall", !apk.exists())
    }

    @Test
    fun uninstallUnknownPackageIsNoOp() = runBlocking {
        repository.register(entity())

        repository.uninstall("com.example.never.installed")

        assertEquals(1, installed().size)
    }

    @Test
    fun disabledExtensionsAreExcludedFromEnabledSet() = runBlocking {
        repository.register(entity())
        repository.setEnabled("eu.kanade.tachiyomi.animeextension.en.example", false)

        assertEquals(1, installed().size)
        assertTrue(enabled().isEmpty())
    }

    @Test
    fun reEnablingRestoresExtension() = runBlocking {
        val pkg = "eu.kanade.tachiyomi.animeextension.en.example"
        repository.register(entity())
        repository.setEnabled(pkg, false)
        repository.setEnabled(pkg, true)

        assertEquals(1, enabled().size)
    }

    // ---- Update detection ----------------------------------------------------

    @Test
    fun newerVersionCodeIsAnUpdate() = runBlocking {
        repository.register(entity(versionCode = 10, versionName = "1.0"))

        assertTrue(repository.isUpdate(entity(versionCode = 11, versionName = "1.1")))
    }

    @Test
    fun sameVersionCodeIsNotAnUpdate() = runBlocking {
        repository.register(entity(versionCode = 10, versionName = "1.0"))

        assertTrue(!repository.isUpdate(entity(versionCode = 10, versionName = "1.0")))
    }

    @Test
    fun olderVersionCodeIsNotAnUpdate() = runBlocking {
        repository.register(entity(versionCode = 10, versionName = "1.0"))

        // A repo serving an older build must not downgrade an installed extension.
        assertTrue(!repository.isUpdate(entity(versionCode = 9, versionName = "0.9")))
    }

    @Test
    fun changedBinaryAtSameVersionCodeIsAnUpdate() = runBlocking {
        repository.register(entity(versionCode = 10, versionName = "1.0", signatureHash = "aaa"))

        assertTrue(
            repository.isUpdate(
                entity(versionCode = 10, versionName = "1.0", signatureHash = "bbb"),
            ),
        )
    }

    @Test
    fun uninstalledExtensionIsAlwaysAnUpdate() = runBlocking {
        assertTrue(repository.isUpdate(entity()))
    }

    // ---- Lookup -------------------------------------------------------------

    @Test
    fun findsInstalledExtensionByPackage() = runBlocking {
        repository.register(entity())

        assertEquals(
            "eu.kanade.tachiyomi.animeextension.en.example",
            repository.find("eu.kanade.tachiyomi.animeextension.en.example")?.packageName,
        )
        assertNull(repository.find("com.example.missing"))
    }

    // ---- Source mapping ------------------------------------------------------

    @Test
    fun sourceIdForExtensionFollowsAniyomiScheme() {
        // The value stored in `library_entries.source_id`. Must be stable and derived only
        // from the extension's package name, never from its version.
        val id = ExtensionRepository.sourceIdFor(
            "eu.kanade.tachiyomi.animeextension.en.example",
        )

        assertEquals("eu.kanade.tachiyomi.animeextension.en.example", id)
    }

    private fun entity(
        packageName: String = "eu.kanade.tachiyomi.animeextension.en.example",
        versionCode: Long = 1,
        versionName: String = "1.0",
        signatureHash: String = "hash",
        apkPath: String = "/data/extensions/example.apk",
    ) = ExtensionEntity(
        packageName = packageName,
        label = "Example",
        versionName = versionName,
        versionCode = versionCode,
        apkPath = apkPath,
        signatureHash = signatureHash,
    )

    /** Minimal in-memory stand-in; the real DAO is covered by the Room suite. */
    private class FakeExtensionDao : com.auris.app.data.local.ExtensionDao {
        private val rows = linkedMapOf<String, ExtensionEntity>()

        override fun observeInstalled(): Flow<List<ExtensionEntity>> = flowOf(rows.values.toList())
        override fun observeEnabled(): Flow<List<ExtensionEntity>> =
            flowOf(rows.values.filter { it.enabled }.toList())

        override suspend fun enabledNow(): List<ExtensionEntity> =
            rows.values.filter { it.enabled }

        override suspend fun find(packageName: String): ExtensionEntity? = rows[packageName]
        override suspend fun upsert(extension: ExtensionEntity) {
            rows[extension.packageName] = extension
        }

        override suspend fun upsertAll(extensions: List<ExtensionEntity>) {
            extensions.forEach { rows[it.packageName] = it }
        }

        override suspend fun setEnabled(packageName: String, enabled: Boolean) {
            rows[packageName]?.let { rows[packageName] = it.copy(enabled = enabled) }
        }

        override suspend fun delete(packageName: String) {
            rows.remove(packageName)
        }

        override fun observeEnabledCount(): Flow<Int> =
            flowOf(rows.values.count { it.enabled })
    }
}