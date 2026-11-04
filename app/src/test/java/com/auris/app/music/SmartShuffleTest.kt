package com.auris.app.music

import com.auris.app.core.model.MediaType
import com.auris.app.data.remote.music.MusicCollectionItem
import com.auris.app.data.remote.music.MusicLibraryMapper
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.data.remote.music.MusicTrack
import com.auris.app.data.remote.music.ResolvedAudio
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleTest {
    private fun track(id: String, artist: String?, title: String = id, duration: Long? = 180_000) = MusicTrack(
        id, title, artist, null, duration, null, null, null, null,
    )

    @Test
    fun excludesPodcastsAndArtistSubstringMatchesButKeepsCollaborations() {
        val candidates = MusicSuggestions.rank(listOf(
            track("song", "Bailey Zimmerman"),
            track("podcast", "Sep 16, 2025"),
            track("collaboration", "Diplo & Bailey Zimmerman"),
            track("unknown", null),
        ), mapOf("bailey zimmerman" to 1.0), emptySet(), 3)
        assertTrue(candidates.any { it.videoId == "song" })
        assertTrue(candidates.any { it.videoId == "collaboration" })
        assertFalse(candidates.any { it.videoId in setOf("podcast", "unknown") })
        assertEquals(emptyList<MusicTrack>(), MusicSuggestions.rank(
            listOf(track("wrong", "Cherry")), mapOf("cher" to 1.0), emptySet(), 3,
        ))
    }

    @Test
    fun excludesQueuedTracksDuplicateUploadsAndLongVideos() {
        val song = track("new", "BAILEY ZIMMERMAN", "New Song")
        assertEquals(listOf(song), MusicSuggestions.rank(listOf(
            track("queued", "Bailey Zimmerman"), song, song,
            track("reupload", "Bailey Zimmerman", "New Song"),
            track("interview", "Bailey Zimmerman", duration = 3_600_000),
        ), mapOf("bailey zimmerman" to 1.0), setOf("queued"), 5))
    }

    @Test
    fun spreadsArtistsInsteadOfFillingShelfWithHighestAffinityArtist() {
        val ranked = MusicSuggestions.rank(listOf(
            track("a1", "A"), track("a2", "A"), track("a3", "A"), track("b1", "B"),
        ), mapOf("a" to 5.0, "b" to 3.0), emptySet(), 3)
        assertEquals(listOf("a1", "b1", "a2"), ranked.map { it.videoId })
    }

    @Test
    fun affinityUsesRecencyFavoritesAndSeedWithoutIncludingOtherMedia() {
        val recent = MusicLibraryMapper.toEntry(track("recent", "Recent - Topic"), installedAtMs = 1_000_000_000)
        val favorite = MusicLibraryMapper.toEntry(track("favorite", "Favorite"), installedAtMs = 1).copy(isFavorite = true)
        val other = recent.copy(mediaType = MediaType.ANIME, author = "Anime author")
        val affinity = MusicSuggestions.artistAffinity(listOf(recent, favorite, other), "Seed - Topic", 1_000_000_000)
        assertEquals("seed", affinity.keys.first())
        assertTrue(affinity.getValue("favorite") > affinity.getValue("recent"))
        assertFalse("anime author" in affinity)
        assertTrue("recent" in affinity)
    }

    @Test
    fun partialSearchFailureKeepsSuggestionsAndExcludesLibraryTracks() = runTest {
        val known = track("known", "A")
        val fresh = track("fresh", "A")
        val history = listOf(
            MusicLibraryMapper.toEntry(known),
            MusicLibraryMapper.toEntry(track("b", "B")),
        )
        val source = object : MusicSource {
            override val discoverySeeds = emptyList<String>()
            override suspend fun search(query: String, limit: Int): Result<List<MusicCollectionItem>> =
                if (query == "a") Result.success(listOf(MusicCollectionItem(MusicCollectionItem.Kind.TRACK, tracks = listOf(known, fresh))))
                else Result.failure(IllegalStateException("offline"))
            override suspend fun collection(browseId: String, limit: Int) = Result.success(emptyList<MusicCollectionItem>())
            override suspend fun resolveAudioStream(videoId: String): Result<ResolvedAudio> = error("Suggestions should not resolve streams")
        }
        assertEquals(listOf(fresh), MusicSuggestions.suggest(source, history))
    }

    @Test
    fun noHistoryProducesNoPersonalizedSuggestions() {
        assertTrue(MusicSuggestions.artistAffinity(emptyList()).isEmpty())
        assertTrue(MusicSuggestions.rank(listOf(track("song", "A")), emptyMap(), emptySet(), 3).isEmpty())
    }
}
