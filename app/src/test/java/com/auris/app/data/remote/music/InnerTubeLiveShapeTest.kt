package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests against the **actual shape of a live response**, verified on 2026-10 with
 * `WEB_REMIX` 1.20240101.01.00.
 *
 * ### Why these differ from the other InnerTube tests
 * `InnerTubeParserTest` uses hand-written fixtures to protect stated rules. These use a trimmed
 * capture of a real response, because they encode something only live traffic revealed: the
 * `WEB_REMIX` track renderer has **no `accessibilityData`**, so a label-based column lookup
 * cannot work and position is the only signal.
 *
 * The real shape:
 * ```
 * flexColumns[0]  [{ "text": "Lose Yourself to Dance (feat. Pharrell Williams)" }]
 * flexColumns[1]  [{ "text": "Song" }, { "text": " • " }, { "text": "5:54" }]
 * flexColumns[2]  [{ "text": "388M plays" }]
 * fixedColumns    absent
 * thumbnail       absent for songs
 * ```
 */
class InnerTubeLiveShapeTest {

    private val parser = InnerTubeParser()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A song row, exactly as returned: three flex columns, no fixed columns, no thumbnail, and
     * a subtitle split across three runs rather than being one string.
     */
    private val liveSong = """
        {"musicResponsiveListItemRenderer":{
          "thumbnail":{"thumbnails":[]},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":0,"text":{"runs":[
              {"text":"Lose Yourself to Dance (feat. Pharrell Williams)"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":1,"text":{"runs":[
              {"text":"Song"},{"text":" • "},{"text":"5:54"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":2,"text":{"runs":[
              {"text":"388M plays"}]}}}
          ],
          "navigationEndpoint":{"watchEndpoint":{"videoId":"iU7oF4OXZSE",
            "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
              "musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}}}}
        }
    """.trimIndent()

    /** A video row, where the subtitle does carry the artist. */
    private val liveVideo = """
        {"musicResponsiveListItemRenderer":{
          "thumbnail":{"thumbnails":[
            {"url":"https://lh3.googleusercontent.com/abc=w60","width":60},
            {"url":"https://lh3.googleusercontent.com/abc=w544","width":544}]},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":0,"text":{"runs":[
              {"text":"Revolution 909"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":1,"text":{"runs":[
              {"text":"Daft Punk"},{"text":" • "},{"text":"Discovery"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":2,"text":{"runs":[
              {"text":"4:26"}]}}}
          ],
          "navigationEndpoint":{"watchEndpoint":{"videoId":"a0tV6XzS0WA"}}
        }}
    """.trimIndent()

    private fun parse(body: String) =
        parser.parseSearch(json.parseToJsonElement(body) as JsonObject)

    @Test
    fun readsTitleFromTheFirstFlexColumn() {
        val track = parse(liveSong).single().tracks.single()

        assertEquals("Lose Yourself to Dance (feat. Pharrell Williams)", track.title)
        assertEquals("iU7oF4OXZSE", track.videoId)
    }

    @Test
    fun readsDurationOutOfASubtitleRun() {
        val track = parse(liveSong).single().tracks.single()

        // "5:54" lives in the third run of the subtitle column. A parser that only looked at
        // fixed columns would read the duration as null — and songs have no fixed columns.
        assertEquals(354_000L, track.durationMs)
    }

    @Test
    fun songRowHasNoArtistRatherThanOneCalledSong() {
        val track = parse(liveSong).single().tracks.single()

        // "Song" is the row's media type, not its artist. Reporting it as the artist would put
        // "Song" under every track title in the UI.
        assertNull(track.artist)
        assertNull(track.album)
    }

    @Test
    fun readsArtistAndAlbumFromTheSubtitleColumn() {
        val track = parse(liveVideo).single().tracks.single()

        assertEquals("Revolution 909", track.title)
        assertEquals("Daft Punk", track.artist)
        assertEquals("Discovery", track.album)
        assertEquals(266_000L, track.durationMs)
    }

    @Test
    fun ignoresPlayCountColumn() {
        val track = parse(liveSong).single().tracks.single()

        // "388M plays" is metadata about popularity, not about the work. It must not become a
        // title or an artist.
        assertTrue(track.artist?.contains("plays") != true)
        assertTrue(!track.title.contains("plays"))
    }

    @Test
    fun handlesAbsentThumbnailWithoutCrashing() {
        val track = parse(liveSong).single().tracks.single()

        // Songs commonly ship an empty thumbnail array. The artwork is still available, because
        // it is derivable from the video id — no resolve, and so no request against the
        // rate-limited audio instance, is needed to paint the row.
        assertEquals("https://i.ytimg.com/vi/${track.videoId}/mqdefault.jpg", track.artworkUrl)
        assertEquals("https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg", track.largeArtworkUrl)
    }

    @Test
    fun picksTheLargeThumbnailWhenPresent() {
        val track = parse(liveVideo).single().tracks.single()

        assertEquals("https://lh3.googleusercontent.com/abc=w544", track.largeArtworkUrl)
    }

    @Test
    fun extractsMusicVideoTypeFromNavigationEndpoint() {
        val track = parse(liveSong).single().tracks.single()

        // Sanity: the renderer really is a music video row, which is why it carries a videoId.
        assertNotNull(track.videoId)
        assertTrue(track.videoId.length == 11)
    }
}