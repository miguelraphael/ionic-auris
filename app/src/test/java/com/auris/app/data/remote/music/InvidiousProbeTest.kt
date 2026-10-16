package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Invidious probe's reduction.
 *
 * ### What these protect, and what they do not
 * The counts, the chosen URL and the `stream=` verdict are the *entire* output, and they are read
 * by hand off a phone screen. A reduction bug would not crash anything — it would send back a
 * confident, wrong answer about whether this instance can serve audio, and a resolver would be
 * built on it. These tests pin the reduction.
 *
 * They say nothing about the network. Whether `invidious.f5.si` serves audio to a *handset* is the
 * open question, and only the device can answer it.
 */
class InvidiousProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `audio formats are counted and the highest bitrate wins`() {
        val summary = parseInvidiousVideo(
            body = """
                {
                  "title": "Never Gonna Give You Up",
                  "adaptiveFormats": [
                    {"itag":140,"type":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":"128000","url":"https://r/aac"},
                    {"itag":251,"type":"audio/webm; codecs=\"opus\"","bitrate":"160000","url":"https://r/opus"},
                    {"itag":137,"type":"video/mp4; codecs=\"avc1.640028\"","bitrate":"4000000","url":"https://r/v"}
                  ]
                }
            """.trimIndent(),
            json = json,
        )

        assertEquals("Never Gonna Give You Up", summary.title)
        assertEquals(2, summary.audioFormats)
        assertEquals(3, summary.totalFormats)
        // Opus at 160 kbps beats AAC at 128, and the 4 Mbps *video* format is not a candidate.
        assertEquals("https://r/opus", summary.bestAudioUrl)
        assertNull(summary.error)
    }

    @Test
    fun `progressive formats count toward the total but are not treated as audio`() {
        val summary = parseInvidiousVideo(
            body = """
                {"title":"T","formatStreams":[{"itag":18,"type":"video/mp4; codecs=\"avc1\"","url":"https://r/muxed"}]}
            """.trimIndent(),
            json = json,
        )

        // They carry audio inside a muxed video track — usable in a pinch, but playing one for a
        // music app wastes an order of magnitude of bandwidth. Counting it as "audio" would hide
        // that an instance offers no audio-only stream at all.
        assertEquals(0, summary.audioFormats)
        assertEquals(1, summary.totalFormats)
        assertNull(summary.bestAudioUrl)
    }

    @Test
    fun `an error field on a 200 is not read as a track nobody has`() {
        val summary = parseInvidiousVideo(
            body = """{"error":"This video is not available"}""",
            json = json,
        )

        assertEquals("This video is not available", summary.error)
        assertEquals(0, summary.totalFormats)
    }

    @Test
    fun `a format without a direct url yields no stream url`() {
        val summary = parseInvidiousVideo(
            body = """
                {"title":"T","adaptiveFormats":[
                  {"itag":251,"type":"audio/webm","bitrate":"160000","signatureCipher":"s=abc&url=https%3A%2F%2Fr%2Fx"}
                ]}
            """.trimIndent(),
            json = json,
        )

        assertEquals(1, summary.audioFormats)
        // The URL is ciphered, so there is nothing to GET. The probe must report the absence
        // rather than invent a URL from the cipher text.
        assertNull(summary.bestAudioUrl)
    }

    @Test
    fun `a non-JSON body becomes an error rather than a zero-format success`() {
        val summary = parseInvidiousVideo(
            body = "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
            json = json,
        )

        assertTrue(summary.error!!.startsWith("unparseable body"))
        assertEquals(0, summary.totalFormats)
    }

    @Test
    fun `the report states the stream verdict, which is the whole point`() {
        val delivered = InvidiousProbeResult(
            instance = "invidious.f5.si",
            httpStatus = 200,
            title = "Never Gonna Give You Up",
            audioFormats = 26,
            totalFormats = 26,
            streamHttpStatus = 206,
            streamBytes = 65536,
        )
        assertTrue(delivered.report().contains("stream=206/65536"))
        assertTrue(delivered.report().contains("audio=26"))

        // 200 from the API with no bytes: the failure a status code alone would hide.
        val empty = delivered.copy(streamHttpStatus = 200, streamBytes = 0)
        assertTrue(empty.report().contains("stream=200/0"))

        // An instance that named no URL at all is a different finding from one that failed.
        val noUrl = delivered.copy(streamHttpStatus = null, streamBytes = 0)
        assertTrue(noUrl.report().contains("stream=-"))

        val broke = delivered.copy(streamHttpStatus = null, streamError = "timeout")
        assertTrue(broke.report().contains("stream=ERR"))
        assertTrue(broke.report().contains("streamError=\"timeout\""))
    }

    @Test
    fun `an unreachable instance reports its error without inventing counts`() {
        val result = InvidiousProbeResult(instance = "inv.nadeko.net", error = "Failed to connect")

        val line = result.report()
        assertTrue(line.startsWith("inv.nadeko.net"))
        assertTrue(line.contains("http=-"))
        assertTrue(line.contains("error=\"Failed to connect\""))
    }
}
