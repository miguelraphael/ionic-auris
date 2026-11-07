package com.auris.app.data.remote.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auris.app.TestApplication
import com.auris.app.music.buildMediaItem
import com.auris.app.ui.music.artistHint
import com.auris.app.ui.music.toRows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApplication::class)
class InnerTubeAlbumSearchTest {
    private fun album(id: String) = """{"musicTwoRowItemRenderer":{
        "title":{"runs":[{"text":"Album $id"}]},
        "subtitle":{"runs":[{"text":"Album • Chase Atlantic • 2026"}]},
        "navigationEndpoint":{"browseEndpoint":{"browseId":"MPRE$id"}}
    }}"""

    private fun song(id: String, artist: String? = null): String {
        val subtitle = artist?.let { """, "subtitle":{"runs":[{"text":"$it"}]}""" }.orEmpty()
        return """{"musicResponsiveListItemRenderer":{
            "title":{"runs":[{"text":"Song $id"}]},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"$id"}}$subtitle
        }}"""
    }

    private fun client(server: MockWebServer) = InnerTubeClient(
        OkHttpClient(), Json, baseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test fun userSearchExpandsAlbumsUsingResponseEndpointWithoutCrowdingOutSongs() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"musicShelfRenderer":{
                "title":{"runs":[{"text":"Albums"}]},
                "bottomEndpoint":{"searchEndpoint":{"params":"current-album-filter"}},
                "contents":[${song("song")},${album("one")}]
            }}"""))
            server.enqueue(MockResponse().setBody("""{"contents":[${album("one")},${album("two")},${album("three")}]}"""))

            val results = client(server).search("Chase Atlantic", limit = 3, includeAlbumResults = true).getOrThrow()

            assertEquals(listOf("song"), results.flatMap { it.tracks }.map { it.videoId })
            assertEquals(listOf("MPREone", "MPREtwo", "MPREthree"), results.mapNotNull { it.album?.browseId })
            server.takeRequest()
            val request = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("Chase Atlantic", request.getValue("query").jsonPrimitive.content)
            assertEquals("current-album-filter", request.getValue("params").jsonPrimitive.content)
        }
    }

    @Test fun discoverySearchDoesNotExpandAlbumShelf() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"musicShelfRenderer":{
                "title":{"runs":[{"text":"Albums"}]},
                "bottomEndpoint":{"searchEndpoint":{"params":"current-album-filter"}},
                "contents":[${album("one")}]
            }}"""))
            assertEquals(1, client(server).search("Discovery").getOrThrow().size)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun failedAlbumExpansionKeepsTheOriginalSearchResults() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"musicShelfRenderer":{
                "title":{"runs":[{"text":"Albums"}]},
                "bottomEndpoint":{"searchEndpoint":{"params":"current-album-filter"}},
                "contents":[${song("song")},${album("one")}]
            }}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            val results = client(server).search("Artist", includeAlbumResults = true).getOrThrow()
            assertEquals("song", results.flatMap { it.tracks }.single().videoId)
            assertEquals("MPREone", results.mapNotNull { it.album }.single().browseId)
        }
    }

    @Test fun albumHeaderFillsMissingArtistAndAlbumWithoutOverwritingGuestArtist() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{
                "header":{"musicResponsiveHeaderRenderer":{
                    "title":{"runs":[{"text":"Lost In Heaven"}]},
                    "straplineTextOne":{"runs":[{"text":"Chase Atlantic - Topic",
                        "navigationEndpoint":{"browseEndpoint":{"browseId":"UCchase"}}}]}
                }},
                "contents":[${song("escort")},${song("guest", "Guest Artist • Other Album")}]
            }"""))

            val items = client(server).collection("MPREalbum").getOrThrow()
            val tracks = items.flatMap { it.tracks }
            assertEquals("Chase Atlantic", tracks[0].artist)
            assertEquals("Lost In Heaven", tracks[0].album)
            assertEquals("Guest Artist", tracks[1].artist)
            assertEquals("Other Album", tracks[1].album)
            val rows = items.flatMap { it.toRows() }
            assertEquals("Song escort", rows[0].title)
            assertEquals("Chase Atlantic", rows[0].artistHint())
            assertEquals("Guest Artist", rows[1].artistHint())
            val queued = buildMediaItem(rows[0].id, rows[0].title, rows[0].artistHint(), tracks[0].album,
                rows[0].artworkUrl, "https://audio.example/escort")
            assertEquals("Chase Atlantic", queued.mediaMetadata.artist.toString())
            assertEquals("Song escort", queued.mediaMetadata.title.toString())
        }
    }

    @Test fun legacyAlbumHeaderAndMultipleArtistsRemainReadable() {
        val parser = InnerTubeParser()
        val legacy = Json.parseToJsonElement("""{"musicDetailHeaderRenderer":{
            "title":{"runs":[{"text":"Album"}]},
            "subtitle":{"runs":[{"text":"Album • Actual Artist • 2026"}]}
        }}""").jsonObject
        assertEquals("Album" to "Actual Artist", parser.albumMetadata(legacy))
        val multiple = Json.parseToJsonElement("""{"musicResponsiveHeaderRenderer":{
            "straplineTextOne":{"runs":[
                {"text":"Artist One","navigationEndpoint":{"browseEndpoint":{"browseId":"UCone"}}},
                {"text":" & "},
                {"text":"Artist Two","navigationEndpoint":{"browseEndpoint":{"browseId":"UCtwo"}}}
            ]}
        }}""").jsonObject
        assertEquals("Artist One, Artist Two", parser.albumMetadata(multiple)?.second)
    }
}
