package com.auris.app.music

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.auris.app.TestApplication
import com.auris.app.ui.music.MusicRow
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class MusicLocalCollectionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val song = MusicRow(MusicRow.Kind.TRACK, "Stay", "Cueshé", "https://example.com/art.jpg", "song-one")

    @Before fun clear() {
        context.getSharedPreferences("music_collections", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun playlistsSurviveReopeningAndKeepArtworkAndOrder() {
        val store = MusicLocalCollections(context)
        store.add("Favorites", song)
        val id = store.playlists().single().id
        val second = song.copy(id = "song-two", title = "Another song")
        store.add("Favorites", second, id)
        val restored = MusicLocalCollections(context).playlists().single()
        assertEquals("Favorites", restored.name)
        assertEquals(listOf(song, second), restored.tracks)
    }

    @Test fun addingSameSongTwiceDoesNotDuplicateIt() {
        val store = MusicLocalCollections(context)
        store.add("Mix", song)
        store.add("Mix", song, store.playlists().single().id)
        assertEquals(listOf(song), store.playlists().single().tracks)
    }

    @Test fun separatePlaylistsCanHaveSameName() {
        val store = MusicLocalCollections(context)
        store.add("Mix", song)
        store.add("Mix", song.copy(id = "two"))
        assertEquals(2, store.playlists().map { it.id }.distinct().size)
    }
}
