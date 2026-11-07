package com.auris.app.source.repo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExtensionInstallConsentTest {
    @Test fun cancellationStopsInstallContinuation() = runBlocking {
        var persistedOrLoaded = false
        try {
            requireExtensionInstallConsent(SignatureVerdict.Pinned("exact-signer")) { false }
            persistedOrLoaded = true
            fail("Cancelled installation continued")
        } catch (_: IllegalStateException) {
            assertFalse(persistedOrLoaded)
        }
    }

    @Test fun approvalPromptsForExactObservedSigner() = runBlocking {
        var requested: String? = null
        requireExtensionInstallConsent(SignatureVerdict.Pinned("exact-signer")) {
            requested = it
            true
        }
        assertEquals("exact-signer", requested)
    }

    @Test fun trustedUpdatesDoNotPrompt() = runBlocking {
        requireExtensionInstallConsent(SignatureVerdict.Trusted) {
            fail("Existing trusted signer must not prompt")
            false
        }
    }

    @Test fun rejectedSignerCannotBeOverriddenByConsent() = runBlocking {
        try {
            requireExtensionInstallConsent(SignatureVerdict.Rejected("Wrong signer")) {
                fail("Rejected signer must not prompt")
                true
            }
            fail("Rejected installation continued")
        } catch (expected: IllegalStateException) {
            assertEquals("Wrong signer", expected.message)
        }
    }
}
