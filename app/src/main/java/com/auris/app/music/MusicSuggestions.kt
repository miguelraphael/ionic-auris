package com.auris.app.music

import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.data.remote.music.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Local, explainable recommendations shared by Music home and Smart Shuffle. */
internal object MusicSuggestions {
    private fun normalized(value: String): String = value.removeSuffix(" - Topic")
        .trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun matches(artist: String?, name: String): Boolean = artist != null &&
        Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(normalized(name))}($|[^\\p{L}\\p{N}])")
            .containsMatchIn(normalized(artist))

    /** Recency decays over weeks; favorites contribute extra affinity, without inventing play counts. */
    internal fun artistAffinity(
        history: List<LibraryEntryEntity>,
        seedArtist: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<String, Double> {
        val weights = linkedMapOf<String, Double>()
        history.filter { it.mediaType == MediaType.MUSIC && !it.isDeleted }
            .sortedByDescending { it.lastActivityAt }.take(30).forEach { entry ->
                val artist = entry.author?.let(::normalized)?.takeIf { it.isNotBlank() } ?: return@forEach
                val ageWeeks = (nowMs - entry.lastActivityAt).coerceAtLeast(0L) / 604_800_000.0
                weights[artist] = (weights[artist] ?: 0.0) + 1.0 / (1.0 + ageWeeks) +
                    if (entry.isFavorite) 2.0 else 0.0
            }
        seedArtist?.let(::normalized)?.takeIf { it.isNotBlank() }?.let {
            weights[it] = (weights[it] ?: 0.0) + 5.0
        }
        return weights.entries.sortedByDescending { it.value }.associate { it.toPair() }
    }

    internal fun rank(
        candidates: List<MusicTrack>,
        affinity: Map<String, Double>,
        excludedIds: Set<String>,
        limit: Int,
    ): List<MusicTrack> {
        val remaining = candidates.distinctBy { it.videoId }
            .filter { track ->
                track.videoId !in excludedIds && track.title.isNotBlank() &&
                    (track.durationMs == null || track.durationMs in 30_000L..900_000L) &&
                    affinity.keys.any { matches(track.artist, it) }
            }
            // Different uploads of the same title and artist should not fill the shelf.
            .distinctBy { normalized(it.title) to normalized(it.artist.orEmpty()) }.toMutableList()
        val selected = mutableListOf<MusicTrack>()
        val artistCounts = mutableMapOf<String, Int>()
        while (remaining.isNotEmpty() && selected.size < limit) {
            val best = remaining.maxBy { track ->
                affinity.filterKeys { matches(track.artist, it) }.entries.maxOf { (artist, weight) ->
                    weight / (1.0 + 2.0 * (artistCounts[artist] ?: 0))
                }
            }
            selected += best
            remaining.remove(best)
            affinity.keys.filter { matches(best.artist, it) }.forEach {
                artistCounts[it] = (artistCounts[it] ?: 0) + 1
            }
        }
        return selected
    }

    suspend fun suggest(
        source: MusicSource,
        history: List<LibraryEntryEntity>,
        seedArtist: String? = null,
        excludedIds: Set<String> = emptySet(),
        limit: Int = 15,
    ): List<MusicTrack> {
        val affinity = artistAffinity(history, seedArtist).entries.take(3).associate { it.toPair() }
        val candidates = mutableListOf<MusicTrack>()
        for (artist in affinity.keys) {
            try {
                candidates += source.search(artist, 20).getOrThrow().flatMap { it.tracks }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed artist query should not discard the other artists' suggestions.
            }
        }
        val knownIds = history.filter { it.mediaType == MediaType.MUSIC }.map { it.sourceId }.toSet()
        return withContext(Dispatchers.Default) {
            rank(candidates, affinity, excludedIds + knownIds, limit)
        }
    }
}
