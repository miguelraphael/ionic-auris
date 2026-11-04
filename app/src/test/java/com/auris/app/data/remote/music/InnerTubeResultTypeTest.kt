package com.auris.app.data.remote.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class InnerTubeResultTypeTest {
    private fun parse(type: String?, subtitle: String = "Artist") : MusicTrack {
        val config = type?.let { """, "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$it"}}""" }.orEmpty()
        val body = """{"musicResponsiveListItemRenderer":{
            "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Official Song"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$subtitle"}]}}}
            ],
            "navigationEndpoint":{"watchEndpoint":{"videoId":"id"$config}}
        }}"""
        return InnerTubeParser().parseSearch(Json.parseToJsonElement(body) as JsonObject).single().tracks.single()
    }

    @Test
    fun usesProviderTypeInsteadOfOfficialWordInTitle() {
        assertEquals(MusicResultType.SONG, parse("MUSIC_VIDEO_TYPE_ATV").resultType)
        assertEquals(MusicResultType.OFFICIAL_VIDEO, parse("MUSIC_VIDEO_TYPE_OMV").resultType)
        assertEquals(MusicResultType.USER_VIDEO, parse("MUSIC_VIDEO_TYPE_UGC").resultType)
        assertEquals(MusicResultType.UNKNOWN, parse(null).resultType)
    }

    @Test
    fun catalogueSongLabelIsFallbackButDoesNotOverrideUserUploadType() {
        assertEquals(MusicResultType.SONG, parse(null, "Song • Artist").resultType)
        assertEquals(MusicResultType.USER_VIDEO, parse("MUSIC_VIDEO_TYPE_UGC", "Song • Artist").resultType)
    }
}
