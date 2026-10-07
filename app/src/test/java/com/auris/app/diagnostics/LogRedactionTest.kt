package com.auris.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redaction tests.
 *
 * ### Why this is the most important test in the diagnostics feature
 * The exported file leaves the device through a share sheet — into a chat app, a bug tracker, or
 * an email. Anything left unredacted in it is effectively published. These tests are therefore
 * adversarial: each one asserts that a realistic leak *does not* survive.
 *
 * The converse also matters. Over-redacting costs a debugging round trip, so the tests assert
 * that ordinary diagnostic content is preserved rather than blanket-scrubbed.
 */
class LogRedactionTest {

    private fun redact(line: String) = LogRedactor.redact(line)

    // ---- The credential classes that must never leak -----------------------

    @Test
    fun redactsSupabaseProjectUrl() {
        val line = "GET https://abcdefghijklmnop.supabase.co/rest/v1/library_entries"

        val result = redact(line)

        assertFalse(result.contains("abcdefghijklmnop.supabase.co"))
        assertTrue(result.contains("[redacted]"))
        // The surrounding context survives, or the line is useless for debugging.
        assertTrue(result.contains("rest/v1/library_entries"))
    }

    @Test
    fun redactsSupabaseJwtKey() {
        // The real shape: an anon key is a three-part JWT starting `eyJ`.
        val key = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJyb2xlIjoiYW5vbiIsImlhdCI6MTcwMDAwMDAwMH0." +
            "c2lnbmF0dXJlLXZhbHVlLWhlcmU"
        val line = "Supabase init failed: apikey=$key"

        val result = redact(line)

        assertFalse(result.contains(key))
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertTrue(result.contains("Supabase init failed"))
    }

    @Test
    fun redactsBearerToken() {
        val line = "GET /api/edge/anime Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def"

        val result = redact(line)

        assertFalse(result.contains("eyJhbGciOiJIUzI1NiJ9.abc.def"))
        // The scheme is kept, because knowing *which* auth scheme failed is the diagnostic value.
        assertTrue(result.contains("Bearer"))
        assertTrue(result.contains("[redacted]"))
    }

    @Test
    fun redactsBearerCaseInsensitively() {
        val result = redact("authorization: bearer abc123DEF456ghi789")

        assertFalse(result.contains("abc123DEF456ghi789"))
        assertTrue(result.contains("[redacted]"))
    }

    @Test
    fun redactsCookieHeader() {
        val result = redact("Cookie: session=eyJpYXQiOjE3MDAwMDAwMDB9")

        assertFalse(result.contains("eyJpYXQiOjE3MDAwMDAwMDB9"))
        assertTrue(result.contains("[redacted]"))
    }

    // ---- Content that must survive ------------------------------------------

    @Test
    fun preservesOrdinaryDiagnosticLines() {
        val lines = listOf(
            "09:14:22.119 I/AurisExtension: Loading eu.kanade.tachiyomi.animeextension.en.alpha",
            "09:14:22.401 D/AurisDex: Found 3 class names in classes.dex",
            "09:14:25.010 W/AurisContent: Unit fetch failed for entry-7: connection reset",
            "java.io.IOException: Unexpected end of stream",
            "  at okhttp3.internal.http1.Http1ExchangeCodec.readResponseHeaders",
            "01-02 03:04:05.678  1234  5678 E ExoPlayerImpl: Playback error 2003",
        )

        lines.forEach { line ->
            assertEquals("redaction should not touch: $line", line, redact(line))
        }
    }

    @Test
    fun preservesStackTracesIncludingFilePaths() {
        // File paths are not secrets and are exactly what is needed to diagnose a stack.
        val line = "\tat com.auris.app.source.DexFileReader.read(DexFileReader.kt:118)"

        assertEquals(line, redact(line))
    }

    @Test
    fun preservesExtensionPackageNames() {
        // Extension package names look credential-ish but are public identifiers; scrubbing them
        // would make the extension section useless.
        val line = "Loaded extension: eu.kanade.tachiyomi.animeextension.all.animeonsen"

        assertEquals(line, redact(line))
    }

    @Test
    fun preservesDeviceAndVersionIdentifiers() {
        val line = "Device: Google Pixel 7, Android 14 (API 34), version 1.0 (1)"

        assertEquals(line, redact(line))
    }

    @Test
    fun redactsKeyEvenWithoutALabel() {
        // The most dangerous case: a bare token printed by a library, with nothing saying "key".
        val key = "eyJhbGciOiJIUzI1NiJ9.payloadpart.signaturepart"
        val result = redact("unexpected value $key in response")

        assertFalse(result.contains(key))
        assertTrue(result.contains("unexpected value"))
    }

    @Test
    fun handlesMultipleSecretsOnOneLine() {
        val line = "auth failed: Bearer abc123XYZ and Cookie: sess=999888 and host " +
            "https://myproject.supabase.co"

        val result = redact(line)

        assertFalse(result.contains("abc123XYZ"))
        assertFalse(result.contains("sess=999888"))
        assertFalse(result.contains("myproject.supabase.co"))
    }

    @Test
    fun handlesEmptyLine() {
        assertEquals("", redact(""))
    }

    @Test
    fun isIdempotent() {
        // Applied twice — once by the exporter, once by whatever reads it — must not corrupt.
        val once = redact("token eyJhbGciOiJIUzI1NiJ9.a.b")
        assertEquals(once, redact(once))
    }
}