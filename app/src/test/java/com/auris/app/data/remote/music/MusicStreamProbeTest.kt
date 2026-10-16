package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the stream-probe reduction.
 *
 * ### What these protect, and what they do not
 * The counts and the `playabilityStatus` are the *entire* output of the probe, and they are read
 * by hand off a phone screen. A reduction bug would therefore not crash anything — it would send
 * back a confident, wrong answer about whether the network serves audio, and the wrong decision
 * would be made from it. These tests pin the reduction.
 *
 * They say nothing about the network. Whether `/player` answers `OK` on a handset is the open
 * question, and only a device can answer it.
 */
class MusicStreamProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `refusal is reported with its reason and the title the server still named`() {
        val result = parseStreamProbe(
            clientName = "WEB_REMIX",
            clientVersion = "1.20240101.01.00",
            httpStatus = 200,
            body = """
                {"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"},
                 "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Never Gonna Give You Up"}}
            """.trimIndent(),
            json = json,
        )

        assertEquals("WEB_REMIX", result.clientName)
        assertEquals(200, result.httpStatus)
        assertEquals("UNPLAYABLE", result.playabilityStatus)
        assertEquals("Video unavailable", result.reason)
        assertEquals(0, result.audioFormats)
        assertEquals(0, result.totalFormats)
        // The title is the tell that the request was well-formed and the *server* declined.
        assertEquals("Never Gonna Give You Up", result.title)

        val line = result.report()
        assertTrue(line.contains("status=UNPLAYABLE"))
        assertTrue(line.contains("reason=\"Video unavailable\""))
    }

    @Test
    fun `only audio adaptive formats are counted as audio`() {
        val result = parseStreamProbe(
            clientName = "WEB_REMIX",
            clientVersion = "v",
            httpStatus = 200,
            body = """
                {"playabilityStatus":{"status":"OK"},
                 "streamingData":{"adaptiveFormats":[
                   {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","url":"https://r/a"},
                   {"itag":251,"mimeType":"audio/webm; codecs=\"opus\"","signatureCipher":"s=abc&url=https%3A%2F%2Fr%2Fb"},
                   {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","url":"https://r/v"}
                 ]}}
            """.trimIndent(),
            json = json,
        )

        assertEquals("OK", result.playabilityStatus)
        assertEquals(2, result.audioFormats)
        assertEquals(3, result.totalFormats)
    }

    @Test
    fun `progressive formats count too, so a non-adaptive client is not read as a refusal`() {
        val result = parseStreamProbe(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            httpStatus = 200,
            body = """
                {"playabilityStatus":{"status":"OK"},
                 "streamingData":{"formats":[{"itag":140,"mimeType":"audio/mp4","url":"https://r/a"}]}}
            """.trimIndent(),
            json = json,
        )

        assertEquals(1, result.audioFormats)
        assertEquals(1, result.totalFormats)
    }

    @Test
    fun `a non-JSON body becomes an error rather than a zero-format success`() {
        val result = parseStreamProbe(
            clientName = "WEB",
            clientVersion = "v",
            httpStatus = 200,
            body = "<!DOCTYPE html><html><body>not json</body></html>",
            json = json,
        )

        // The distinction matters: "no formats" reads as a refusal, and a refused request is a
        // different finding from one that never got a JSON answer at all.
        assertNotNull(result.error)
        assertNull(result.playabilityStatus)
        assertEquals(0, result.totalFormats)
    }

    @Test
    fun `an absent playability status is not invented`() {
        val result = parseStreamProbe(
            clientName = "IOS",
            clientVersion = "19.29.1",
            httpStatus = 200,
            body = """{"responseContext":{"visitorData":"abc"}}""",
            json = json,
        )

        assertNull(result.playabilityStatus)
        assertNull(result.reason)
        assertTrue(result.report().contains("status=-"))
    }
}
