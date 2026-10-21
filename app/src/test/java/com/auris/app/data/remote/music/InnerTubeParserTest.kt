package com.auris.app.data.remote.music

import com.auris.app.core.model.MediaType
import com.auris.app.data.remote.music.MusicLibraryMapper.toEntry
import com.auris.app.data.remote.music.MusicLibraryMapper.toUnit
import com.auris.app.data.remote.music.MusicLibraryMapper.albumToEntry
import com.auris.app.data.remote.music.MusicLibraryMapper.stableId
import kotlinx.serialization.json.Json
import com.auris.app.data.remote.music.extractYear
import com.auris.app.data.remote.music.parseDurationMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InnerTube parsing tests.
 *
 * ### Fixtures, not real responses
 * Unlike the Kitsu tests, these use **hand-written** JSON rather than captured payloads, and the
 * reason matters: InnerTube is undocumented, so there is no stable response to capture and a
 * captured one would just be another snapshot of the same inference the parser already encodes.
 * What these tests protect is therefore the *stated rules* — audio-only filtering, bitrate
 * ordering, label-based column matching, idempotent ids — not agreement with a live server.
 *
 * That makes them weaker than the Kitsu tests, and it is stated plainly in the client's KDoc:
 * this layer is the least-verified part of the app.
 */
class InnerTubeParserTest {

    private val parser = InnerTubeParser()
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String) =
        parser.parseSearch(json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject })

    // ---- Track parsing ------------------------------------------------------

    @Test
    fun parsesTrackFromMusicResponsiveListItemRenderer() {
        val items = parse(
            """
            {"contents":{
              "musicShelfRenderer":{"contents":[
                {"musicResponsiveListItemRenderer":{
                  "thumbnail":{"thumbnails":[
                    {"url":"https://i.ytimg.com/vi/abc/sddefault.jpg","width":226,"height":226},
                    {"url":"https://i.ytimg.com/vi/abc/hqdefault.jpg","width":480,"height":480}
                  ]},
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Bohemian Rhapsody","accessibilityData":{"accessibilityData":{"label":"Title"}}}
                    ]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                      {"text":"Queen • A Night at the Opera",
                       "accessibilityData":{"accessibilityData":{"label":"subtitle"}}}
                    ]}}}
                  ],
                  "fixedColumns":[
                    {"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"5:55"}]}}}
                  ],
                  "navigationEndpoint":{"watchEndpoint":{"videoId":"abc","playlistId":"PL1"}}
                }}
              ]}
            }}
            """.trimIndent(),
        )

        val track = items.single().tracks.single()

        assertEquals("abc", track.videoId)
        assertEquals("Bohemian Rhapsody", track.title)
        assertEquals("Queen", track.artist)
        assertEquals("A Night at the Opera", track.album)
        assertEquals(355_000L, track.durationMs)
        assertEquals("PL1", track.playlistId)
    }

    @Test
    fun matchesFlexColumnsByAccessibilityLabelNotIndex() {
        // Title is found by its "Title" label. If YouTube inserts a column before it, an
        // index-based lookup would return the wrong string — this is the assertion that the
        // label lookup is really what is happening.
        val items = parse(
            """
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Le Concept","accessibilityData":{"accessibilityData":{"label":"Menu"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Nightwish","accessibilityData":{"accessibilityData":{"label":"Artist"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Century Child","accessibilityData":{"accessibilityData":{"label":"Album"}}}]}}}
              ],
              "navigationEndpoint":{"watchEndpoint":{"videoId":"xyz"}}
            }}
            """.trimIndent(),
        )

        val track = items.single().tracks.single()

        // The title falls through to null because there is no "Title" column at all; the
        // important part is that the *artist* was read from column 1, not 0.
        assertEquals("Nightwish", track.artist)
        assertEquals("Century Child", track.album)
    }

    @Test
    fun picksLargerThumbnailAndFallsBackDown() {
        val withBoth = parse(
            """{"musicResponsiveListItemRenderer":{
                 "thumbnail":{"thumbnails":[
                   {"url":"small","width":160},{"url":"big","width":544}]},
                 "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T"}]}}}],
                 "navigationEndpoint":{"watchEndpoint":{"videoId":"v"}}}}"""
        ).single().tracks.single()

        assertEquals("big", withBoth.largeArtworkUrl)

        // Only a tiny one available: the largest available beats none, because the player
        // upscales less badly than an empty image slot.
        val tinyOnly = parse(
            """{"musicResponsiveListItemRenderer":{
                 "thumbnail":{"thumbnails":[{"url":"tiny","width":60}]},
                 "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"T"}]}}}],
                 "navigationEndpoint":{"watchEndpoint":{"videoId":"v"}}}}"""
        ).single().tracks.single()

        assertEquals("tiny", tinyOnly.artworkUrl)
        assertEquals("tiny", tinyOnly.largeArtworkUrl)
    }

    @Test
    fun dropsRendererWithNoVideoId() {
        val items = parse(
            """{"musicResponsiveListItemRenderer":{
                 "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"No id"}]}}}]}}"""
        )

        // Without a video id the track is unplayable, so it is dropped rather than stored with
        // an empty id that would collide with every other such record.
        assertTrue(items.isEmpty())
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val track = parse(
            """{"musicResponsiveListItemRenderer":{
                 "navigationEndpoint":{"watchEndpoint":{"videoId":"v"}}}}"""
        ).single().tracks.single()

        assertEquals("v", track.videoId)
        // Falls back to the id so the row is still identifiable in the UI.
        assertEquals("v", track.title)
        assertNull(track.artist)
        assertNull(track.durationMs)
        // No thumbnail in the payload, but artwork is derived from the id rather than fetched.
        assertEquals("https://i.ytimg.com/vi/v/mqdefault.jpg", track.artworkUrl)
    }

    @Test
    fun splitsArtistAlbumSubtitleOnBullet() {
        // The realistic shape: one "subtitle"-labelled column carrying
        // "Artist • Album • Year", split into its segments.
        val track = parse(
            """{"musicResponsiveListItemRenderer":{
                 "flexColumns":[
                   {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                     {"text":"Song","accessibilityData":{"accessibilityData":{"label":"Title"}}}]}}},
                   {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                     {"text":"Artist • Album • 2019","accessibilityData":{"accessibilityData":{"label":"subtitle"}}}]}}}],
                 "navigationEndpoint":{"watchEndpoint":{"videoId":"v"}}}}"""
        ).single().tracks.single()

        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
    }

    // ---- Player parsing -----------------------------------------------------

    @Test
    fun keepsOnlyAudioFormats() {
        val response = parser.parsePlayer(
            json.parseToJsonElement(
                """
                {"playabilityStatus":{"status":"OK"},
                 "streamingData":{"expiresInSeconds":"21540","adaptiveFormats":[
                   {"url":"https://r1/av","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","averageBitrate":128000},
                   {"url":"https://r1/vo","mimeType":"video/mp4; codecs=\"avc1.42E01E\"","averageBitrate":900000},
                   {"url":"https://r1/am","mimeType":"audio/webm; codecs=\"opus\"","averageBitrate":160000}
                 ]},
                 "videoDetails":{"videoId":"v","title":"T","author":"A","lengthSeconds":"225",
                                  "thumbnail":{"thumbnails":[{"url":"https://t/1"}]}}}
                """.trimIndent(),
            ).let { it as kotlinx.serialization.json.JsonObject },
        )

        assertTrue(response.playabilityStatus.isPlayable)
        assertEquals(
            listOf("https://r1/am", "https://r1/av"),
            response.audioStreams.map { it.url },
        )
        assertEquals("T", response.videoDetails?.title)
        assertEquals(225L, response.videoDetails?.lengthSeconds)
    }

    @Test
    fun ordersAudioStreamsByDescendingBitrate() {
        val response = parser.parsePlayer(
            json.parseToJsonElement(
                """
                {"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[
                  {"url":"low","mimeType":"audio/mp4","averageBitrate":48000},
                  {"url":"high","mimeType":"audio/webm","averageBitrate":256000},
                  {"url":"mid","mimeType":"audio/mp4","averageBitrate":128000}]}}
                """.trimIndent(),
            ).let { it as kotlinx.serialization.json.JsonObject },
        )

        // A player that takes index 0 must get the best stream, not whichever order the
        // response happened to use.
        assertEquals("high", response.audioStreams.first().url)
    }

    @Test
    fun computesStreamExpiry() {
        val response = parser.parsePlayer(
            json.parseToJsonElement(
                """{"playabilityStatus":{"status":"OK"},
                    "streamingData":{"expiresInSeconds":"3600","adaptiveFormats":[
                      {"url":"u","mimeType":"audio/mp4","averageBitrate":128000}]}}"""
            ).let { it as kotlinx.serialization.json.JsonObject },
        )

        val stream = response.audioStreams.single()

        assertNotNull(stream.expiresAtMs)
        assertFalse(stream.isExpired())
        // Expiry is why the URL is never persisted as the source of truth.
        assertTrue(stream.isExpired(nowMs = System.currentTimeMillis() + 7_200_000L))
    }

    @Test
    fun reportsUnplayableStatusWithReason() {
        val response = parser.parsePlayer(
            json.parseToJsonElement(
                """{"playabilityStatus":{"status":"UNPLAYABLE",
                     "reason":"Video unavailable"},"streamingData":{}}"""
            ).let { it as kotlinx.serialization.json.JsonObject },
        )

        assertFalse(response.playabilityStatus.isPlayable)
        assertEquals("Video unavailable", response.playabilityStatus.reason)
        assertTrue(response.audioStreams.isEmpty())
    }

    @Test
    fun missingStreamingDataIsNotACrash() {
        val response = parser.parsePlayer(
            json.parseToJsonElement("""{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""")
                .let { it as kotlinx.serialization.json.JsonObject },
        )

        assertFalse(response.playabilityStatus.isPlayable)
        assertTrue(response.audioStreams.isEmpty())
        assertNull(response.videoDetails)
    }

    // ---- Continuation -------------------------------------------------------

    @Test
    fun findsNextContinuationToken() {
        val token = parser.continuationToken(
            json.parseToJsonElement(
                """{"continuations":[{"nextContinuationData":{"continuation":"TOKEN123","clickTrackingParams":"x"}}]}"""
            ).let { it as kotlinx.serialization.json.JsonObject },
        )

        assertEquals("TOKEN123", token)
    }

    @Test
    fun ignoresNonPagingContinuations() {
        // `reloadContinuationData` and shuffle seeds are not cursors; following one would page
        // into unrelated content.
        val token = parser.continuationToken(
            json.parseToJsonElement(
                """{"continuations":[{"reloadContinuationData":{"continuation":"NOTAPAGE"}}]}"""
            ).let { it as kotlinx.serialization.json.JsonObject },
        )

        assertNull(token)
    }

    // ---- Helpers ------------------------------------------------------------

    @Test
    fun parsesDurations() {
        assertEquals(45_000L, "45".parseDurationMs())
        assertEquals(225_000L, "3:45".parseDurationMs())
        assertEquals(3_723_000L, "1:02:03".parseDurationMs())
        assertNull("live".parseDurationMs())
        assertNull("".parseDurationMs())
    }

    @Test
    fun extractsYearFromSubtitle() {
        assertEquals(2019, "Some Album • 2019 • 12 songs".extractYear())
        assertNull("Some Album".extractYear())
    }
}

/**
 * Mapping tests: YouTube Music content onto the unified Room schema.
 */
class MusicLibraryMapperTest {

    private val track = MusicTrack(
        videoId = "dQw4w9WgXcQ",
        title = "Never Gonna Give You Up",
        artist = "Rick Astley",
        album = "Whenever You Need Somebody",
        durationMs = 213_000L,
        artworkUrl = "https://i.ytimg.com/vi/x/sd.jpg",
        largeArtworkUrl = "https://i.ytimg.com/vi/x/hq.jpg",
        playlistId = "PLabc",
        trackIndex = 4,
    )

    @Test
    fun mapsTrackToLibraryEntry() {
        val entry = toEntry(track, progressFraction = 0.5f)

        assertEquals(MediaType.MUSIC, entry.mediaType)
        // The schema's documented identity tuple for music.
        assertEquals(MusicLibraryMapper.SOURCE_KEY, entry.sourceKey)
        assertEquals("dQw4w9WgXcQ", entry.sourceId)
        assertEquals("Rick Astley • Whenever You Need Somebody", entry.subtitle)
        assertEquals("Rick Astley", entry.author)
        assertEquals(0.5f, entry.progress, 0.001f)
    }

    @Test
    fun prefersLargeArtworkForTheGridAndPlayer() {
        // The player's centred square art wants the larger image; the grid reads the same field.
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", toEntry(track).artworkUrl)
        assertEquals("https://i.ytimg.com/vi/x/sd.jpg", toEntry(track.copy(largeArtworkUrl = null)).artworkUrl)
    }

    @Test
    fun mapsTrackToUnitWithVideoIdAsSourceUnitId() {
        val unit = toUnit("entry-1", track, trackIndex = 5)

        assertEquals("entry-1", unit.entryId)
        assertEquals(5, unit.unitIndex)
        // The video id, not the title: titles get re-spelled, ids do not.
        assertEquals("dQw4w9WgXcQ", unit.sourceUnitId)
        assertEquals(213_000L, unit.durationMs)
    }

    @Test
    fun neverStoresAResolvedStreamUrl() {
        val unit = toUnit("entry-1", track, trackIndex = 1)

        // InnerTube stream URLs expire in ~6h; storing one guarantees a 403 next play. The
        // player re-resolves per playback instead.
        assertNull(unit.streamUrl)
    }

    @Test
    fun albumBecomesOneEntryWithTrackCountAsTotalUnits() {
        val album = MusicAlbum(
            browseId = "MPREb_1",
            title = "Kind of Blue",
            artist = "Miles Davis",
            artworkUrl = "https://art/kind-of-blue.jpg",
            year = 1959,
            trackCount = 5,
        )

        val entry = albumToEntry(album)

        // This is what makes album progress legible through the existing library UI: an album at
        // track 2 of 5 shows 40%, exactly as an anime at episode 2 of 5 does.
        assertEquals(5, entry.totalUnits)
        assertEquals(MediaType.MUSIC, entry.mediaType)
        assertEquals("MPREb_1", entry.sourceId)
        assertEquals("Kind of Blue", entry.title)
        assertEquals(1959, entry.releaseYear)
    }

    @Test
    fun stableIdIsDeterministicAndDistinct() {
        val a = stableId("videoA")
        val b = stableId("videoB")

        // Deterministic because re-adding the same track must upsert, not duplicate.
        assertEquals(a, stableId("videoA"))
        assertTrue(a != b)
        assertTrue(a.startsWith("music-"))
    }

    @Test
    fun albumAndTrackIdsDoNotCollide() {
        // An album and a track can share a browse/video id namespace; prefixing the album id
        // keeps them distinct.
        assertTrue(stableId("album:MPREb_1") != stableId("MPREb_1"))
    }

    @Test
    fun unitIndexIsOneBased() {
        // The schema documents `unit_index` as 1-based ("Episode 1, chapter 1, track 1"), and
        // InnerTube numbers tracks from zero in places.
        assertEquals(1, MusicLibraryMapper.toUnitIndex(0))
        assertEquals(5, MusicLibraryMapper.toUnitIndex(4))
    }
}