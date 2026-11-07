package com.auris.app.source.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryUrlTest {
    @Test fun acceptsHttpsRepository() {
        assertTrue(isSecureRepositoryUrl("https://example.org/repo"))
    }

    @Test fun rejectsUnencryptedMalformedAndCredentialBearingUrls() {
        listOf("http://example.org/repo", "https://", "not a url",
            "https://user:password@example.org/repo", "https://user@example.org/repo").forEach {
            assertFalse(it, isSecureRepositoryUrl(it))
        }
    }
}
