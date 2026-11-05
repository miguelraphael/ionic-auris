package com.auris.app.data.remote.music

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AlbumBrowseArtworkTest {
    private val parser = InnerTubeParser()
    private val contents = Json.parseToJsonElement(
        javaClass.getResource("/music/search-collections.json")!!.readText()).jsonObject
    private val header = Json.parseToJsonElement("""{"musicResponsiveHeaderRenderer":{
        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
        {"url":"https://art/album-120.jpg","width":120},
        {"url":"https://art/album-1200.jpg","width":1200}]}}}}}""")
    private val root get() = JsonObject(contents + ("header" to header))

    @Test fun selectsLargestCoverFromAlbumHeader() {
        assertEquals("https://art/album-1200.jpg", parser.albumArtwork(root))
    }

    @Test fun doesNotMistakeSongOrArtistThumbnailsForAlbumCover() {
        assertNull(parser.albumArtwork(contents))
        val artist = JsonObject(contents + ("header" to Json.parseToJsonElement(
            """{"musicImmersiveHeaderRenderer":{"thumbnail":{"thumbnails":[{"url":"https://art/artist.jpg","width":1200}]}}}""")))
        assertNull(parser.albumArtwork(artist))
    }

    @Test fun appliesExactCoverToAlbumTracksButNotPlaylists() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(root.toString().toResponseBody()).build()
        }.build()
        val client = InnerTubeClient(http, Json)
        val albumTracks = client.collection("MPRE-test-album", 20).getOrThrow().flatMap { it.tracks }
        assertTrue(albumTracks.isNotEmpty())
        assertTrue(albumTracks.all { it.albumArtworkUrl == "https://art/album-1200.jpg" &&
            it.artworkUrl == "https://art/album-1200.jpg" && it.largeArtworkUrl == "https://art/album-1200.jpg" })
        val playlistTracks = client.collection("VL-test-playlist", 20).getOrThrow().flatMap { it.tracks }
        assertTrue(playlistTracks.all { it.albumArtworkUrl == null })
    }
}
