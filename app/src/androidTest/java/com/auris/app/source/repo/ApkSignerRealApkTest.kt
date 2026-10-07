package com.auris.app.source.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Signature verification against a **real, third-party signed APK**.
 *
 * ### Why this runs on a device rather than the JVM
 * [ApkSigner] reads the APK Signature Scheme block through [android.content.pm.PackageManager],
 * and that block is the whole point. A hand-written fixture cannot prove the reader works
 * against a real signing block — and the common failure here is silent: a v2/v3-only APK has no
 * signature file under `META-INF` at all, so a parser built on `CertificateFactory` reports a
 * correctly-signed APK as *unsigned*, and every extension then looks tampered with.
 *
 * The fixture is the published extension `eu.kanade.tachiyomi.animeextension.all.myreadingmanga`,
 * signed by its real publisher, so the fingerprint below is whatever that APK genuinely carries
 * rather than a value chosen by this project.
 */
@RunWith(AndroidJUnit4::class)
class ApkSignerRealApkTest {

    /**
     * Assets live in the **test** APK; scratch files live in the **app under test**.
     *
     * Both contexts are needed and using the wrong one fails in a way that misleads:
     * - `ApplicationProvider` returns the app under test. Its `filesDir` is writable, but its
     *   assets are a *different set* — the real APK is not among them, and the failure surfaces
     *   as `FileNotFoundException` for an asset that is visibly present in the test APK.
     * - The instrumentation context holds the right assets, but its package data directory has
     *   no writable `files` subdirectory, so writing the scratch APK there fails with ENOENT.
     *
     * This split is the same one `ExtensionTestSupport` already uses for the mock extension.
     */
    private val appContext
        get() = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()

    private val assetContext
        get() = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().context

    /** Copies the fixture out of assets so `PackageManager` can read it as a file. */
    private fun copyRealApk(): File {
        val target = File(appContext.filesDir, "real-extension.apk")
        if (target.exists()) target.delete()

        val asset = "real-mangasource-extension.apk"
        assetContext.assets.open(asset).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        // DexClassLoader refuses a writable dex, for the same reason an installed extension APK
        // must be read-only: so a tampered file cannot be swapped in before the next load.
        target.setReadOnly()
        return target
    }

    private fun fingerprints(): Set<String> =
        ApkSigner.fingerprints(appContext.packageManager, copyRealApk().absolutePath)

    @Test
    fun readsAFingerprintFromARealSignedApk() {
        val observed = fingerprints()

        // The core assertion: a genuinely signed third-party APK yields a real fingerprint.
        // If this returns empty, everything downstream silently treats the APK as unsigned.
        assertTrue("no fingerprint read from a real signed APK", observed.isNotEmpty())
        observed.forEach { fingerprint ->
            assertEquals("fingerprint is not canonical lower-case hex", 64, fingerprint.length)
            assertEquals(fingerprint, SignatureVerifier.normalize(fingerprint))
        }
    }

    @Test
    fun theRealFingerprintIsAcceptedByTheVerifier() {
        val observed = fingerprints().toSet()

        val verdict = SignatureVerifier.verify(
            declaredFingerprint = observed.first(),
            observed = observed,
            previouslyPinned = null,
        )

        assertEquals(SignatureVerdict.Trusted, verdict)
    }

    @Test
    fun aDifferentKeyIsRejectedForTheRealApk() {
        val verdict = SignatureVerifier.verify(
            declaredFingerprint = "0".repeat(64),
            observed = fingerprints(),
            previouslyPinned = null,
        )

        assertTrue(verdict is SignatureVerdict.Rejected)
        assertFalse(verdict.isInstallable)
    }

    @Test
    fun aMissingFileYieldsNoFingerprintsRatherThanThrowing() {
        // Install-time code must be able to treat "unreadable" as "no signatures" and fail the
        // install, rather than crash the install flow on an IO error.
        val observed = ApkSigner.fingerprints(
            appContext.packageManager,
            File(appContext.filesDir, "does-not-exist.apk").absolutePath,
        )

        assertTrue(observed.isEmpty())
    }

    @Test
    fun aFileThatIsNotAnApkYieldsNoFingerprints() {
        val notAnApk = File(appContext.cacheDir, "not-an-apk.apk")
        notAnApk.writeText("this is not an apk")

        val observed = ApkSigner.fingerprints(appContext.packageManager, notAnApk.absolutePath)

        assertTrue(observed.isEmpty())
    }
}
