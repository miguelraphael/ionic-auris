package com.auris.app.data.remote.music

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanArtistTest {
    @Test
    fun stripsTopicSuffixesWithVariousDashesAndSeparators() {
        assertEquals("Chase Atlantic", "Chase Atlantic - Topic".cleanArtist())
        assertEquals("Chase Atlantic", "Chase Atlantic – Topic".cleanArtist())
        assertEquals("Chase Atlantic", "Chase Atlantic — Topic".cleanArtist())
        assertEquals("Chase Atlantic", "Chase Atlantic • Topic".cleanArtist())
        assertEquals("Chase Atlantic", "Chase Atlantic, Topic".cleanArtist())
        assertEquals("Chase Atlantic", "Chase Atlantic - Topic - Releases".cleanArtist())
        assertEquals("The Weeknd", "The Weeknd - Topic".cleanArtist())
        assertEquals("Taylor Swift", "Taylor Swift - topic".cleanArtist())
    }

    @Test
    fun preservesCleanArtistNamesWithoutTopicSuffix() {
        assertEquals("Chase Atlantic", "Chase Atlantic".cleanArtist())
        assertEquals("Queen", "Queen".cleanArtist())
        assertEquals("Daft Punk", "Daft Punk".cleanArtist())
    }

    @Test
    fun preservesTopicWhenItIsTheActualArtistName() {
        assertEquals("Topic", "Topic".cleanArtist())
    }
}
