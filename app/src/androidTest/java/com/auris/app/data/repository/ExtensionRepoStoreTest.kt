package com.auris.app.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.source.repo.ExtensionRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.runner.RunWith

/**
 * The persisted repository trust settings.
 *
 * ### What is actually being protected here
 * The URL and the signing key are one trust decision with two consumers: Settings edits it, the
 * install path verifies against it. The bugs these tests pin were both ways that decision could
 * be lost while still *looking* correct:
 *
 * - Settings and Extensions each held their own copy, so a key typed in one was not the key
 *   checked in the other. The field would render, save, and change nothing.
 * - Nothing was persisted, so both vanished on process death and every extension silently fell
 *   back to trust-on-first-use. A user who believes they pinned a repository, and did not.
 *
 * The second is the more dangerous of the two, because the app keeps working perfectly and the
 * user has no way to notice they are being trusted on nothing.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionRepoStoreTest {

    private lateinit var store: ExtensionRepoStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A fresh store per test: SharedPreferences outlives the process, so a leftover value
        // would make these tests order-dependent in exactly the way this class exists to prevent.
        context.getSharedPreferences("auris_extension_repo", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        store = ExtensionRepoStore(context)
    }

    @Test
    fun startsAtTheDefaultRepository() {
        assertEquals(ExtensionRepo.DEFAULT.baseUrl, store.current().baseUrl)
    }

    @Test
    fun survivesANewStoreInstance() {
        // The persistence assertion. A second instance stands in for the next app launch, which
        // is where an in-memory-only trust decision would be lost.
        store.save(
            ExtensionRepo(
                baseUrl = "https://example.test/repo/index.min.json",
                name = "Example",
                signingKeyFingerprint = "a".repeat(64),
            ),
        )

        val reopened = ExtensionRepoStore(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
        )

        assertEquals("https://example.test/repo/index.min.json", reopened.current().baseUrl)
        assertEquals("a".repeat(64), reopened.current().signingKeyFingerprint)
    }

    @Test
    fun observersSeeUpdates() {
        var observed = ""
        // Driven synchronously so the assertion cannot race the collector.
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { store.repo.collect { observed = it.signingKeyFingerprint } }

        store.update { it.copy(signingKeyFingerprint = "b".repeat(64)) }

        assertEquals(
            "a stored fingerprint must reach observers, or Settings and Extensions disagree",
            "b".repeat(64),
            observed,
        )
        job.cancel()
    }

    @Test
    fun clearingTheKeyIsDistinctFromLeavingIt() {
        // An empty fingerprint is meaningful — it declares "this repository publishes no key" —
        // so it must round-trip rather than being confused with "never configured".
        store.update { it.copy(signingKeyFingerprint = "c".repeat(64)) }
        store.update { it.copy(signingKeyFingerprint = "") }

        assertEquals("", store.current().signingKeyFingerprint)
        assertFalse(SignatureVerifierProbe.isWellFormed(store.current().signingKeyFingerprint))
    }

    @Test
    fun resetRestoresTheDefault() {
        store.update { it.copy(baseUrl = "https://evil.test/repo", signingKeyFingerprint = "d".repeat(64)) }

        store.reset()

        assertEquals(ExtensionRepo.DEFAULT.baseUrl, store.current().baseUrl)
        assertTrue(store.current().signingKeyFingerprint.isEmpty())
    }

    @Test
    fun updateChangesOnlyWhatItIsAskedTo() {
        val before = store.current().copy(signingKeyFingerprint = "e".repeat(64))

        store.update { it.copy(signingKeyFingerprint = "f".repeat(64)) }

        val after = store.current()
        assertEquals("f".repeat(64), after.signingKeyFingerprint)
        assertEquals(before.baseUrl, after.baseUrl)
        assertEquals(before.name, after.name)
    }
}

/** Indirection so the test reads as behaviour rather than importing the verifier directly. */
private object SignatureVerifierProbe {
    fun isWellFormed(value: String?) =
        com.auris.app.source.repo.SignatureVerifier.isWellFormed(value)
}
