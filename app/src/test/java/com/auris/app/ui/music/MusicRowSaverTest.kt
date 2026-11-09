package com.auris.app.ui.music

import androidx.compose.runtime.saveable.SaverScope
import com.auris.app.data.remote.music.MusicResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicRowSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test fun artistAndAlbumContextSurviveNavigationStateSaving() {
        for (kind in listOf(MusicRow.Kind.ARTIST, MusicRow.Kind.ALBUM, MusicRow.Kind.PLAYLIST)) {
            val row = MusicRow(kind, "A title", "An artist", "https://example.com/cover", "browse-id",
                MusicResultType.SONG, "https://example.com/album")
            val saved = with(MusicRowSaver) { scope.save(row)!! }
            assertEquals(row, MusicRowSaver.restore(saved))
        }
    }

    @Test fun emptySelectionAndMissingArtworkSurviveSaving() {
        assertNull(MusicRowSaver.restore(with(MusicRowSaver) { scope.save(null)!! }))
        val row = MusicRow(MusicRow.Kind.ARTIST, "Artist", null, null, "artist-id")
        assertEquals(row, MusicRowSaver.restore(with(MusicRowSaver) { scope.save(row)!! }))
    }
}
