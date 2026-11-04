package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class InnerTubeCollectionsTest {
    private val parser = InnerTubeParser()
    private fun parse(body: String) = parser.parseSearch(Json.parseToJsonElement(body) as JsonObject)

    @Test
    fun readsSongsAlbumsPlaylistsAndNestedArtFromTrimmedLiveCapture() {
        // WEB_REMIX, "i love you so", 2026-11-04. Tracking fields removed.
        val body = javaClass.getResource("/music/search-collections.json")!!.readText()
        val items = parse(body)
        assertEquals(2, items.sumOf { it.tracks.size })
        val album = items.mapNotNull { it.album }.single()
        val playlist = items.mapNotNull { it.playlist }.single()
        assertTrue(album.browseId.startsWith("MPRE"))
        assertTrue(playlist.browseId.startsWith("VL"))
        assertEquals("I love you so", playlist.title)
        assertTrue(playlist.subtitle!!.contains("Langlang Tadlas"))
        assertNotNull(album.artworkUrl)
        assertNotNull(playlist.artworkUrl)
        assertEquals("I Love You So", items.first().tracks.single().title)
        assertEquals("The Walters", items.first().tracks.single().artist)
        // The artist result is not an album, playlist, or playable song.
        assertEquals(4, items.size)
    }

    @Test
    fun sectionHeadingIsNotAPlaylist() {
        assertTrue(parse("""{"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]}}}""").isEmpty())
    }

    @Test
    fun twoRowPlaylistIsNotAnAlbumAndUsesItsOwnEndpoint() {
        val playlist = parse("""{"musicTwoRowItemRenderer":{
            "title":{"runs":[{"text":"My Mix"}]},
            "subtitle":{"runs":[{"text":"Creator","navigationEndpoint":{"browseEndpoint":{"browseId":"UCartist"}}}]},
            "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLmix"}},
            "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://art/mix","width":300}]}}}
        }}""").single().playlist!!
        assertEquals("VLPLmix", playlist.browseId)
        assertEquals("https://art/mix", playlist.artworkUrl)
    }
}
