package com.auris.app.ui.music

import com.auris.app.data.remote.music.MusicAlbum
import com.auris.app.data.remote.music.MusicCollectionItem
import com.auris.app.data.remote.music.MusicPlaylist
import com.auris.app.data.remote.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The mapping from catalogue answers to rows.
 *
 * ### Why it is pinned
 * The same function fills both the search list and the home shelves. A drift here would change
 * both surfaces at once — an album that stopped being recognised would vanish from search *and*
 * from "Top Picks", which is the sort of bug that looks like "the provider broke" rather than a
 * one-line mapping change. The tests also pin the display rule that a track with no artist and
 * no album keeps a null subtitle instead of showing a stray " • ".
 */
class MusicBrowseRowsTest {

    private fun track(
        videoId: String = "vid1",
        title: String = "A Song",
        artist: String? = "An Artist",
        album: String? = null,
    ) = MusicTrack(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000L,
        artworkUrl = "https://img/$videoId-small.jpg",
        largeArtworkUrl = "https://img/$videoId-large.jpg",
        playlistId = null,
        trackIndex = null,
    )

    @Test
    fun mapsTrackWithArtistAndAlbum() {
        val rows = MusicCollectionItem(
            kind = MusicCollectionItem.Kind.TRACK,
            tracks = listOf(track(album = "An Album")),
        ).toRows()

        assertEquals(1, rows.size)
        assertEquals(MusicRow.Kind.TRACK, rows.single().kind)
        assertEquals("A Song", rows.single().title)
        assertEquals("An Artist • An Album", rows.single().subtitle)
        assertEquals("https://img/vid1-small.jpg", rows.single().artworkUrl)
        assertEquals("vid1", rows.single().id)
    }

    @Test
    fun trackWithNoArtistOrAlbumHasNoSubtitle() {
        val rows = MusicCollectionItem(
            kind = MusicCollectionItem.Kind.TRACK,
            tracks = listOf(track(artist = null, album = null)),
        ).toRows()

        // Not " • " or a blank string: the row simply has no second line.
        assertNull(rows.single().subtitle)
    }

    @Test
    fun keepsCompositeOrderAndIdentity() {
        val rows = MusicCollectionItem(
            kind = MusicCollectionItem.Kind.UNKNOWN,
            tracks = listOf(track(videoId = "vid1")),
            album = MusicAlbum(
                browseId = "album1",
                title = "An Album",
                artist = "An Artist",
                artworkUrl = "https://img/album.jpg",
                year = 2024,
                trackCount = 10,
            ),
            playlist = MusicPlaylist(
                browseId = "playlist1",
                title = "A Playlist",
                subtitle = "Someone",
                artworkUrl = "https://img/playlist.jpg",
                trackCount = 20,
            ),
        ).toRows()

        assertEquals(
            listOf(MusicRow.Kind.TRACK, MusicRow.Kind.ALBUM, MusicRow.Kind.PLAYLIST),
            rows.map { it.kind },
        )
        // Track ids come from the video, albums/playlists from their browse ids: playback and
        // collection loading use different endpoints.
        assertEquals("vid1", rows[0].id)
        assertEquals("album1", rows[1].id)
        assertEquals("playlist1", rows[2].id)
    }

    @Test
    fun emptyCollectionProducesNoRows() {
        assertEquals(
            emptyList<MusicRow>(),
            MusicCollectionItem(kind = MusicCollectionItem.Kind.UNKNOWN).toRows(),
        )
    }
}
