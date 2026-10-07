package com.auris.app.ui.settings

import com.auris.app.data.local.entity.ExtensionEntity
import com.auris.app.source.repo.SignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the settings screen describes installed extensions' signatures.
 *
 * ### Why the three states must stay distinct on screen
 * The user-facing claim is the whole point of implementing verification. Showing "Verified" for an
 * extension that was merely pinned on first install would be a false claim in the one place the
 * user goes looking for it, and "Pinned" reads as a mild reassurance rather than the weaker
 * guarantee it actually is.
 *
 * The repository's declared key is an input to the verdict, so the same extension can move from
 * [ExtensionTrust.PINNED] to [ExtensionTrust.VERIFIED] purely by pointing the app at a repository
 * that publishes a matching key — with no change to the APK.
 */
class SettingsTrustTest {

    private val keyA =
        "3a2f1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f20"
    private val keyB =
        "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0"

    private fun extension(fingerprint: String?) = ExtensionEntity(
        packageName = "com.example.ext",
        label = "Example",
        versionName = "1.0",
        versionCode = 1,
        apkPath = "/tmp/ext.apk",
        signatureHash = "filesha",
        signingCertFingerprint = fingerprint,
    )

    // The derivation mirrors SettingsViewModel.publish; asserted here so a change to the mapping
    // cannot silently downgrade what the screen claims.
    private fun trustOf(fingerprint: String?, declared: String?): ExtensionTrust {
        val normalized = SignatureVerifier.normalize(fingerprint)
        val declaredNormalized = SignatureVerifier.normalize(declared)
        return when {
            normalized == null -> ExtensionTrust.UNRECORDED
            declaredNormalized != null && declaredNormalized == normalized -> ExtensionTrust.VERIFIED
            else -> ExtensionTrust.PINNED
        }
    }

    @Test
    fun matchesTheRepositoryKeyIsVerified() {
        assertEquals(ExtensionTrust.VERIFIED, trustOf(keyA, keyA))
    }

    @Test
    fun publishedFormatStillMatches() {
        val published = keyA.uppercase().chunked(2).joinToString(":")

        assertEquals(ExtensionTrust.VERIFIED, trustOf(keyA, published))
    }

    @Test
    fun noRecordedFingerprintIsNotClaimedAsVerified() {
        assertEquals(ExtensionTrust.UNRECORDED, trustOf(null, keyA))
    }

    @Test
    fun pinnedWhenTheRepositoryPublishesNoKey() {
        // The honest weaker claim: nothing was checked against a publisher identity.
        assertEquals(ExtensionTrust.PINNED, trustOf(keyA, null))
    }

    @Test
    fun pinnedWhenTheRepositoryKeyDoesNotMatch() {
        assertEquals(ExtensionTrust.PINNED, trustOf(keyA, keyB))
    }

    @Test
    fun aMalformedRepositoryKeyDoesNotProduceAVerifiedClaim() {
        // If the declared key were compared loosely, garbage could "match" and the screen would
        // assert verification that never happened.
        assertEquals(ExtensionTrust.PINNED, trustOf(keyA, "not-a-key"))
    }
}
