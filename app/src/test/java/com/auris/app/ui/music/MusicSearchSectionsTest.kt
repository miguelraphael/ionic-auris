package com.auris.app.ui.music

import com.auris.app.data.remote.music.MusicResultType
import org.junit.Assert.*
import org.junit.Test

class MusicSearchSectionsTest {
    private fun row(kind: MusicRow.Kind, id: String) = MusicRow(kind, id, null, null, id)

    @Test
    fun albumTapOpensDetailsWithoutStartingPlayback() {
        val opened = mutableListOf<MusicRow>()
        val played = mutableListOf<MusicRow>()
        val album = row(MusicRow.Kind.ALBUM, "MPREalbum")
        selectMusicSearchResult(album, { played += it }, { opened += it })
        assertEquals(listOf(album), opened)
        assertTrue(played.isEmpty())
    }

    @Test
    fun playlistTapOpensDetailsWithoutStartingPlayback() {
        val opened = mutableListOf<MusicRow>()
        val played = mutableListOf<MusicRow>()
        val playlist = row(MusicRow.Kind.PLAYLIST, "VLplaylist")
        selectMusicSearchResult(playlist, { played += it }, { opened += it })
        assertEquals(listOf(playlist), opened)
        assertTrue(played.isEmpty())
    }

    @Test
    fun artistTapOpensArtistPage() {
        val openedArtists = mutableListOf<MusicRow>()
        val openedAlbums = mutableListOf<MusicRow>()
        val played = mutableListOf<MusicRow>()
        val artist = row(MusicRow.Kind.ARTIST, "UCartist")
        selectMusicSearchResult(artist, { played += it }, { openedAlbums += it }, { openedArtists += it })
        assertEquals(listOf(artist), openedArtists)
        assertTrue(openedAlbums.isEmpty())
        assertTrue(played.isEmpty())
    }

    @Test
    fun songTapStillStartsPlayback() {
        val song = row(MusicRow.Kind.TRACK, "song")
        val played = mutableListOf<MusicRow>()
        selectMusicSearchResult(song, { played += it }, { fail("A song is not an album") })
        assertEquals(listOf(song), played)
    }

    @Test
    fun groupsKindsAndDeduplicatesWithoutMergingDifferentKinds() {
        val song = row(MusicRow.Kind.TRACK, "same")
        val album = row(MusicRow.Kind.ALBUM, "same")
        val playlist = row(MusicRow.Kind.PLAYLIST, "mix")
        val grouped = MusicSearchSections(listOf(song, album, playlist, song, row(MusicRow.Kind.PLAYLIST, "")))
        assertEquals(listOf(song), grouped.songs)
        assertEquals(listOf(album), grouped.albums)
        assertEquals(listOf(playlist), grouped.playlists)
        assertEquals(song, grouped.topResult)
    }

    @Test
    fun artistIsTopResultWhenPresent() {
        val artist = row(MusicRow.Kind.ARTIST, "UCartist")
        val song = row(MusicRow.Kind.TRACK, "song")
        val album = row(MusicRow.Kind.ALBUM, "album")
        val sections = MusicSearchSections(listOf(song, artist, album))
        assertEquals(artist, sections.topResult)
        assertEquals(listOf(artist), sections.artists)
    }

    @Test
    fun collectionCanBeTopResultWhenNoSongsMatch() {
        val playlist = row(MusicRow.Kind.PLAYLIST, "mix")
        assertEquals(playlist, MusicSearchSections(listOf(playlist)).topResult)
        assertNull(MusicSearchSections(emptyList()).topResult)
    }

    @Test
    fun promotesReleasesAboveProviderFeaturedVideoAndUnofficialUploads() {
        val upload = row(MusicRow.Kind.TRACK, "upload").copy(resultType = MusicResultType.USER_VIDEO)
        val video = row(MusicRow.Kind.TRACK, "video").copy(resultType = MusicResultType.OFFICIAL_VIDEO)
        val song = row(MusicRow.Kind.TRACK, "song").copy(resultType = MusicResultType.SONG)
        val unknown = row(MusicRow.Kind.TRACK, "unknown")
        val sections = MusicSearchSections(listOf(upload, video, unknown, song))
        assertEquals(song, sections.topResult)
        assertEquals(listOf(song, video, unknown, upload), sections.songs)
    }

    @Test
    fun keepsProviderOrderAmongEquallyRankedSongs() {
        val first = row(MusicRow.Kind.TRACK, "first").copy(resultType = MusicResultType.SONG)
        val second = row(MusicRow.Kind.TRACK, "second").copy(resultType = MusicResultType.SONG)
        assertEquals(listOf(first, second), MusicSearchSections(listOf(first, second)).songs)
    }
}
