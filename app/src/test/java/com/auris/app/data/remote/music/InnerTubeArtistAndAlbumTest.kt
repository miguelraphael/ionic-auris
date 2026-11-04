package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubeArtistAndAlbumTest {

    private val parser = InnerTubeParser()
    private val json = Json { ignoreUnknownKeys = true }

    private fun parseJson(content: String): JsonObject =
        json.parseToJsonElement(content) as JsonObject

    @Test
    fun parseSongContextExtractsAlbumAndArtistFromWatchNext() {
        val jsonPayload = """
        {
          "contents": {
            "singleColumnMusicWatchNextResultsRenderer": {
              "tabbedRenderer": {
                "watchNextTabbedResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "musicQueueRenderer": {
                            "content": {
                              "playlistPanelRenderer": {
                                "contents": [
                                  {
                                    "playlistPanelVideoRenderer": {
                                      "title": { "runs": [{ "text": "Swim" }] },
                                      "longBylineText": {
                                        "runs": [
                                          {
                                            "text": "Chase Atlantic - Topic",
                                            "navigationEndpoint": {
                                              "browseEndpoint": {
                                                "browseId": "UCKT1WQMU1VcGbSua2K8x8XQ",
                                                "browseEndpointContextSupportedConfigs": {
                                                  "browseEndpointContextMusicConfig": {
                                                    "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                  }
                                                }
                                              }
                                            }
                                          },
                                          { "text": " • " },
                                          {
                                            "text": "Chase Atlantic",
                                            "navigationEndpoint": {
                                              "browseEndpoint": {
                                                "browseId": "MPREb_Bj50PqThk32",
                                                "browseEndpointContextSupportedConfigs": {
                                                  "browseEndpointContextMusicConfig": {
                                                    "pageType": "MUSIC_PAGE_TYPE_ALBUM"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        ]
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()

        val context = parser.parseSongContext(parseJson(jsonPayload))
        assertEquals("MPREb_Bj50PqThk32", context.albumBrowseId)
        assertEquals("Chase Atlantic", context.albumTitle)
        assertEquals("UCKT1WQMU1VcGbSua2K8x8XQ", context.artistBrowseId)
        assertEquals("Chase Atlantic", context.artistName) // Topic is cleaned!
    }

    @Test
    fun parseArtistDetailsExtractsOfficialPhotoAndBio() {
        val jsonPayload = """
        {
          "header": {
            "musicImmersiveHeaderRenderer": {
              "title": { "runs": [{ "text": "Chase Atlantic" }] },
              "description": {
                "runs": [
                  { "text": "Chase Atlantic is an Australian R&B band from Cairns, Queensland, formed in 2014.\n\nFrom Wikipedia (https://en.wikipedia.org/wiki/Chase_Atlantic)" }
                ]
              },
              "thumbnails": [
                { "url": "https://art/small.jpg", "width": 400, "height": 200 },
                { "url": "https://art/official_banner_2880.jpg", "width": 2880, "height": 1200 }
              ]
            }
          }
        }
        """.trimIndent()

        val details = parser.parseArtistDetails(parseJson(jsonPayload), defaultName = "Default Artist")
        assertEquals("Chase Atlantic", details.name)
        assertEquals("https://art/official_banner_2880.jpg", details.photoUrl)
        assertEquals(
            "Chase Atlantic is an Australian R&B band from Cairns, Queensland, formed in 2014.",
            details.bio
        )
    }

    @Test
    fun parseMusicItemRecognizesMpspAndRdclakOfficialPlaylists() {
        val payload = """
        {
          "musicTwoRowItemRenderer": {
            "title": { "runs": [{ "text": "Top Hits Official" }] },
            "subtitle": { "runs": [{ "text": "YouTube Music" }] },
            "navigationEndpoint": {
              "browseEndpoint": {
                "browseId": "MPSPPLHe4eP3YsICtANxBl0fqYU_LN-qVGayZj"
              }
            }
          }
        }
        """.trimIndent()

        val items = parser.parseBrowse(parseJson(payload))
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals(MusicCollectionItem.Kind.PLAYLIST, item.kind)
        assertEquals("MPSPPLHe4eP3YsICtANxBl0fqYU_LN-qVGayZj", item.playlist?.browseId)
        assertEquals("Top Hits Official", item.playlist?.title)
    }
}
