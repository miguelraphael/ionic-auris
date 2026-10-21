package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Invidious reduction, shared by the probe and the resolver.
 *
 * ### What these protect, and what they do not
 * The counts, the chosen URL and the `stream=` verdict are the *entire* output of the probe, and
 * they are read by hand off a phone screen. A reduction bug would not crash anything — it would
 * send back a confident, wrong answer about whether an instance can serve audio, and a resolver
 * would be built on it. These tests pin the reduction.
 *
 * They say nothing about the network. Whether `invidious.f5.si` serves audio to a *handset* is a
 * question only a device can answer.
 */
class InvidiousProbeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `audio formats are counted and the highest bitrate wins`() {
        val summary = parseInvidiousVideo(
            body = """
                {
                  "title": "Never Gonna Give You Up",
                  "author": "Rick Astley",
                  "lengthSeconds": 213,
                  "videoThumbnails": [
                    {"quality":"default","url":"https://i/small.jpg","width":120,"height":90},
                    {"quality":"maxresdefault","url":"https://i/big.jpg","width":1280,"height":720}
                  ],
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
        assertEquals("Rick Astley", summary.author)
        assertEquals(213_000L, summary.lengthMs)
        assertEquals(2, summary.audioFormats)
        assertEquals(3, summary.totalFormats)
        // Opus at 160 kbps beats AAC at 128, and the 4 Mbps *video* format is not a candidate
        // while an audio-only one exists.
        assertEquals("https://r/opus", summary.bestAudioUrl)
        assertEquals("audio/webm; codecs=\"opus\"", summary.bestAudioMimeType)
        assertEquals(160000, summary.bestAudioBitrate)
        assertNull(summary.error)
    }

    @Test
    fun `a muxed format is a fallback so an instance with no audio-only stream still plays`() {
        val summary = parseInvidiousVideo(
            body = """
                {"title":"T","formatStreams":[
                  {"itag":18,"type":"video/mp4; codecs=\"avc1\"","bitrate":"600000","url":"https://r/muxed"}
                ]}
            """.trimIndent(),
            json = json,
        )

        // `audioFormats` stays zero — there is no audio-only stream, and reporting one would
        // hide that this instance costs video bandwidth for audio.
        assertEquals(0, summary.audioFormats)
        assertEquals(1, summary.totalFormats)
        // But it is still usable: no surface is attached in an audio session, so only the
        // bandwidth is wasted. Refusing to play would be worse.
        assertEquals("https://r/muxed", summary.bestAudioUrl)
    }

    @Test
    fun `the lowest-bitrate muxed format is chosen when only muxed formats exist`() {
        val summary = parseInvidiousVideo(
            body = """
                {"title":"T","formatStreams":[
                  {"itag":22,"type":"video/mp4","bitrate":"2000000","url":"https://r/big"},
                  {"itag":18,"type":"video/mp4","bitrate":"600000","url":"https://r/small"}
                ]}
            """.trimIndent(),
            json = json,
        )

        // The video track is never rendered, so its bitrate is pure cost.
        assertEquals("https://r/small", summary.bestAudioUrl)
    }

    @Test
    fun `an error field on a 200 is not read as a track nobody has`() {
        val summary = parseInvidiousVideo(
            body = """{"error":"This video is not available"}""",
            json = json,
        )

        assertEquals("This video is not available", summary.error)
        assertEquals(0, summary.totalFormats)
        // A refusal must not produce a stream, or the resolver would play nothing.
        assertNull(summary.toAudioStream())
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
        // The URL is ciphered, so there is nothing to GET. The reduction must report the absence
        // rather than invent a URL from the cipher text.
        assertNull(summary.bestAudioUrl)
        assertNull(summary.toAudioStream())
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
    fun `the stream expiry is read out of the url so a cached url has a deadline`() {
        val summary = parseInvidiousVideo(
            body = """
                {"title":"T","adaptiveFormats":[
                  {"itag":251,"type":"audio/webm","bitrate":"160000",
                   "url":"https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1792155031&itag=251&mime=audio%2Fwebm"}
                ]}
            """.trimIndent(),
            json = json,
        )

        val stream = summary.toAudioStream()!!

        assertEquals(1792155031000L, stream.expiresAtMs)
        // Before the deadline it is playable; at and after it, it is not.
        assertTrue(stream.isPlayable(nowMs = 1792155031000L - 1))
        assertTrue(!stream.isPlayable(nowMs = 1792155031000L))
    }

    @Test
    fun `a url with no expiry is assumed usable rather than refused`() {
        val summary = parseInvidiousVideo(
            body = """
                {"title":"T","adaptiveFormats":[{"itag":251,"type":"audio/webm","url":"https://r/x"}]}
            """.trimIndent(),
            json = json,
        )

        val stream = summary.toAudioStream()!!

        // Refusing a URL whose lifetime is merely unknown would break playback the moment a
        // provider changes its query parameters.
        assertNull(stream.expiresAtMs)
        assertTrue(stream.isPlayable())
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

    @Test
    fun `a multi-line error body is collapsed so one instance stays one line`() {
        // The defect this pins was observed on a device: an nginx 401 page leaked its newlines
        // into the report and split one result across three log entries.
        val result = InvidiousProbeResult(
            instance = "invidious.nerdvpn.de",
            httpStatus = 401,
            error = "<html>\n<head><title>401 Authorization Required</title></head>\n</html>",
        )

        val line = result.report()
        assertTrue("report must be one line", '\n' !in line)
        assertTrue(line.contains("401 Authorization Required"))
    }
}
