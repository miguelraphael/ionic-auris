package com.auris.app.data.remote.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicArtworkTest {
    @Test
    fun requestsLargerAppleAndGoogleCovers() {
        assertEquals("https://is1-ssl.mzstatic.com/image/1200x1200bb.jpg",
            nowPlayingArtworkUrl("https://is1-ssl.mzstatic.com/image/600x600bb.jpg"))
        assertEquals("https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj",
            nowPlayingArtworkUrl("https://lh3.googleusercontent.com/cover=w120-h120-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/cover=s1200",
            nowPlayingArtworkUrl("https://yt3.googleusercontent.com/cover=s192"))
    }

    @Test
    fun usesYouTubeMaxResolutionWithoutOldSizeSignature() {
        assertEquals("https://i.ytimg.com/vi/video/maxresdefault.jpg",
            nowPlayingArtworkUrl("https://i.ytimg.com/vi/video/hqdefault.jpg?sqp=small&rs=signature"))
    }

    @Test
    fun preservesUnknownHostsAndAlreadyLargeImages() {
        val custom = "https://art.example/600x600bb.jpg"
        assertEquals(custom, nowPlayingArtworkUrl(custom))
        val large = "https://i.ytimg.com/vi/video/maxresdefault.jpg"
        assertEquals(large, nowPlayingArtworkUrl(large))
        assertNull(nowPlayingArtworkUrl(null))
    }
}
