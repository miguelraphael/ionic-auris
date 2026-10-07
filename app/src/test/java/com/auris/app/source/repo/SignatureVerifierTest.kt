package com.auris.app.source.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extension-signature trust policy.
 *
 * ### Why this is tested so hard
 * An extension is dex that runs with the app's full permissions — it can read the library
 * database, the Supabase session and the user's cookies. A wrong decision here is either a
 * refused install of a legitimate extension, or, far worse, a silently installed hostile one.
 * Both failure modes are quiet, so each rule below is pinned by a test that states which side of
 * the line it is on.
 */
class SignatureVerifierTest {

    // Two distinct 64-char SHA-256 fingerprints, as [ApkSigner] would render them.
    private val keyA =
        "3a2f1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f20"
    private val keyB =
        "9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0"

    /** The same key as repositories publish it: upper case, colon separated, as keytool prints. */
    private val keyAAsPublished = keyA.uppercase().chunked(2).joinToString(":")

    // ---- Normalisation ------------------------------------------------------

    @Test
    fun normalisesPublishedFormatToCanonicalForm() {
        // The formats genuinely differ in the wild. Without normalisation every real extension
        // would report a mismatch — which is indistinguishable from a real forgery.
        assertEquals(keyA, SignatureVerifier.normalize(keyAAsPublished))
        assertEquals(keyA, SignatureVerifier.normalize(keyA.uppercase()))
        assertEquals(keyA, SignatureVerifier.normalize("  $keyA  "))
    }

    @Test
    fun rejectsMalformedFingerprints() {
        // A short hash must not match a truncated one, and a non-hex string must not be treated
        // as a claim at all.
        assertEquals(null, SignatureVerifier.normalize("abc123"))
        assertEquals(null, SignatureVerifier.normalize("z".repeat(64)))
        assertEquals(null, SignatureVerifier.normalize(""))
        assertEquals(null, SignatureVerifier.normalize(null))
    }

    // ---- Matching the repository's claim ------------------------------------

    @Test
    fun acceptsAPkSignedByTheDeclaredKey() {
        val verdict = SignatureVerifier.verify(keyA, setOf(keyA), previouslyPinned = null)

        assertEquals(SignatureVerdict.Trusted, verdict)
        assertTrue(verdict.isInstallable)
    }

    @Test
    fun acceptsAPublishedFormatDeclaration() {
        val verdict =
            SignatureVerifier.verify(keyAAsPublished, setOf(keyA), previouslyPinned = null)

        assertEquals(SignatureVerdict.Trusted, verdict)
    }

    @Test
    fun rejectsAPkSignedByADifferentKey() {
        val verdict = SignatureVerifier.verify(keyA, setOf(keyB), previouslyPinned = null)

        assertTrue(verdict is SignatureVerdict.Rejected)
        assertFalse(verdict.isInstallable)
    }

    @Test
    fun acceptsWhenAnySignerMatchesBecauseKeysRotate() {
        // Android lets a publisher ship a new key while the old one still validates, so an APK
        // can legitimately carry two certificates. Rejecting because the first one differs
        // would make every key rotation look like a forgery.
        val verdict = SignatureVerifier.verify(keyA, setOf(keyB, keyA), previouslyPinned = null)

        assertEquals(SignatureVerdict.Trusted, verdict)
    }

    @Test
    fun rejectsAnUnsignedApk() {
        // The reason an empty certificate set cannot be treated as "nothing to check".
        val verdict = SignatureVerifier.verify(keyA, emptySet(), previouslyPinned = null)

        assertTrue(verdict is SignatureVerdict.Rejected)
    }

    // ---- Pinning -----------------------------------------------------------

    @Test
    fun pinsOnFirstInstallWhenTheRepositoryDeclaresNoKey() {
        // Trust on first use: allowed so a repo with no published key is usable at all, and the
        // verdict is Pinned rather than Trusted so the UI never calls it verified.
        val verdict = SignatureVerifier.verify(null, setOf(keyA), previouslyPinned = null)

        assertEquals(SignatureVerdict.Pinned(keyA), verdict)
        assertTrue(verdict.isInstallable)
    }

    @Test
    fun anExistingPinIsHonouredOnUpdate() {
        val verdict = SignatureVerifier.verify(null, setOf(keyA), previouslyPinned = keyA)

        assertEquals(SignatureVerdict.Trusted, verdict)
    }

    @Test
    fun aPinOverridesAChangedRepositoryClaim() {
        // The attack this exists for: a compromised repo declares a new key, republishes the
        // index, and ships an APK signed by it. Checking only against the *current* claim would
        // accept it. The pin is what makes the rotation visible instead of silent.
        val verdict = SignatureVerifier.verify(keyB, setOf(keyB), previouslyPinned = keyA)

        assertTrue(verdict is SignatureVerdict.Rejected)
        assertFalse(verdict.isInstallable)
    }

    @Test
    fun pinBeatsDeclarationEvenWhenBothArePresentAndDiffer() {
        val verdict = SignatureVerifier.verify(keyB, setOf(keyB), previouslyPinned = keyA)

        assertTrue(verdict is SignatureVerdict.Rejected)
    }

    // ---- Failing closed on bad configuration -------------------------------

    @Test
    fun aMalformedDeclaredKeyIsRefusedRatherThanIgnored() {
        // Failing open would mean a typo in a repo URL silently disables verification for every
        // extension from that repository — the worst possible outcome for a security check.
        val verdict = SignatureVerifier.verify("not-a-fingerprint", setOf(keyA), null)

        assertTrue(verdict is SignatureVerdict.Rejected)
    }

    @Test
    fun aBlankDeclaredKeyMeansNoClaimRatherThanAMismatch() {
        val verdict = SignatureVerifier.verify("   ", setOf(keyA), null)

        assertEquals(SignatureVerdict.Pinned(keyA), verdict)
    }

    // ---- Comparison helper --------------------------------------------------

    @Test
    fun constantTimeComparisonAgreesWithEquality() {
        assertTrue(SignatureVerifier.constantTimeEquals(keyA, keyA))
        assertFalse(SignatureVerifier.constantTimeEquals(keyA, keyB))
        assertFalse(SignatureVerifier.constantTimeEquals(keyA, keyA.dropLast(1)))
        assertTrue(SignatureVerifier.constantTimeEquals("", ""))
    }
}
