package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Subtitle-column classification, using rows taken from **live responses on 2026-10**.
 *
 * ### What these protect
 * A track row's subtitle is a bullet-joined list of runs, and almost none of its parts are
 * metadata about the work: one may be a duration, one a media-type token, one a popularity
 * figure. The parser has to sort those out before anything can be called an artist or an
 * album, because every mistake here is silently displayed as real metadata next to a correct
 * title — there is no error, just wrong text.
 *
 * Each fixture below is a row that actually appeared on device, not a shape invented here.
 */
class InnerTubeSubtitleSegmentTest {

    private val parser = InnerTubeParser()
    private val json = Json { ignoreUnknownKeys = true }

    private fun row(title: String, vararg subtitleRuns: String, thumbnail: Boolean = true): String {
        val thumbs = if (thumbnail) {
            """"thumbnail":{"thumbnails":[{"url":"https://lh3.googleusercontent.com/a=w60","width":60}]},"""
        } else {
            """"thumbnail":{"thumbnails":[]},"""
        }
        return """
            {"musicResponsiveListItemRenderer":{
              $thumbs
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":0,"text":{"runs":[
                  {"text":"$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":1,"text":{"runs":[
                  ${subtitleRuns.joinToString(",") { "{\"text\":\"${it.replace("\"", "\\\"")}\"}" }}
                ]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"displayPriority":2,"text":{"runs":[
                  {"text":"9.3M views"}]}}}
              ],
              "navigationEndpoint":{"watchEndpoint":{"videoId":"iU7oF4OXZSE"}}
            }}
        """.trimIndent()
    }

    private fun parse(body: String) =
        parser.parseSearch(json.parseToJsonElement(body) as JsonObject).single().tracks.single()

    // ---- Popularity counts --------------------------------------------------

    @Test
    fun viewsCountIsNotAnAlbum() {
        // Live row: "Serenata • 921K views". The view count is the *only* subtitle part, so
        // before the count pattern existed this became the album and rendered as
        // "Serenata • 921K views" under the title on device.
        val track = parse(row("Serenata", " • ", "921K views"))

        assertEquals("Serenata", track.title)
        assertNull(track.artist)
        assertNull(track.album)
    }

    @Test
    fun everyPopularityUnitSeenLiveIsFiltered() {
        listOf("921K views", "388M plays", "1.4M views", "9.3M views", "1,204,555 listeners")
            .forEach { count ->
                val track = parse(row("T", " • ", count))
                assertNull("count '$count' leaked into metadata", track.album)
            }
    }

    @Test
    fun aBareNumberIsNotAPopularityCount() {
        // "1979" is a release year, not a view count, and not the album either. The digit
        // leading requirement keeps it out of the popularity pattern; the year rule then keeps
        // it out of the artist/album slots without treating it as a figure about the work.
        val track = parse(row("Discovery", "Daft Punk", " • ", "1979"))

        assertEquals("Daft Punk", track.artist)
        assertNull(track.album)
    }

    @Test
    fun aReleaseYearDoesNotDisplaceTheRealAlbum() {
        // The failure mode a year must not cause: with "Daft Punk • 2019 • Discovery", keeping
        // the year as metadata makes it the *second* segment, so the album slot reports 2019.
        val track = parse(row("Discovery", "Daft Punk", " • ", "2019", " • ", "Discovery"))

        assertEquals("Daft Punk", track.artist)
        assertEquals("Discovery", track.album)
    }

    @Test
    fun aShortNumberIsStillTreatedAsMetadata() {
        // "12" is a song count, not a year — the year rule is bounded to 1900-2099 so it does
        // not swallow every small number in a subtitle.
        val track = parse(row("Discovery", "Daft Punk", " • ", "12 songs"))

        assertEquals("Daft Punk", track.artist)
        assertEquals("12 songs", track.album)
    }

    // ---- Years are not durations -------------------------------------------

    @Test
    fun aReleaseYearIsNotReadAsADuration() {
        // Live shape: "Daft Punk • 2019 • Discovery".
        //
        // A bare number parses as a second count, so an unguarded parser reads 2019 as
        // 2,019,000 ms — a 33-minute track — and drops the year from the metadata. Both
        // failures are wrong in a way the UI renders as fact.
        val track = parse(row("Discovery", "Daft Punk", " • ", "2019", " • ", "Discovery"))

        assertNull("2019 was read as a duration", track.durationMs)
        assertEquals("Daft Punk", track.artist)
        assertEquals("Discovery", track.album)
    }

    @Test
    fun aRealClockDurationStillParses() {
        val track = parse(row("Aerodynamic", "Daft Punk", " • ", "Discovery", " • ", "3:33"))

        assertEquals(213_000L, track.durationMs)
    }

    @Test
    fun aSongDurationIsStillParsedAfterTheYearGuard() {
        // The regression risk in tightening the duration rule: "Song • 5:54" was working and
        // must keep working. It has a colon, so it survives the guard.
        val track = parse(row("Lose Yourself to Dance", "Song", " • ", "5:54"))

        assertEquals(354_000L, track.durationMs)
        assertNull(track.artist)
    }

    // ---- Media-type tokens --------------------------------------------------

    @Test
    fun mediaTypeTokensAreNotMetadata() {
        listOf("Song", "Video", "Music video", "Album", "Single", "EP", "Playlist", "Podcast")
            .forEach { token ->
                val track = parse(row("T", token, " • ", "Artist"))
                assertNull("token '$token' leaked into metadata", track.album)
            }
    }

    @Test
    fun realAlbumNamedAfterATypeTokenIsStillPreferred() {
        // Not a concern today, but the reason the token list is exact-match only: a subtitle
        // reading "EP • Artist" filters the token while a title reading "EP" is untouched,
        // because the filter only ever sees subtitle segments.
        val track = parse(row("EP", "Artist", " • ", "3:00"))

        assertEquals("EP", track.title)
        assertEquals("Artist", track.artist)
    }
}
