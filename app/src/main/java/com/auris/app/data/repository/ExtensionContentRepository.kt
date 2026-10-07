package com.auris.app.data.repository

import android.util.Log
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.source.ExtensionSourceRegistry
import com.auris.app.source.Page
import com.auris.app.source.SourceItem
import com.auris.app.source.SourceUnit
import com.auris.app.source.StreamTarget
import com.auris.app.source.toMediaSource

/**
 * Resolves a library entry back to the extension it came from, and fetches its content.
 *
 * ### Why this is a repository and not a screen concern
 * A `LibraryEntryEntity` stores `sourceId` (Aniyomi's stable id, as a string) and `sourceKey`
 * (the source-relative URL, e.g. `/anime/frieren`). Turning those back into a live
 * `MediaSource` requires the loaded extension registry, which only exists in
 * [ExtensionSourceRegistry] — a process-wide singleton. A screen reaching into that directly
 * would be unable to be tested and would duplicate the lookup in every caller.
 *
 * ### Failure behaviour
 * A missing source is an expected state, not an error: the extension may have been uninstalled
 * or disabled, or the entry may have been synced from another device where it exists. Every
 * method therefore returns a [Result] and callers can render "this content is unavailable"
 * rather than an error screen. A network failure is likewise distinct from an empty list — a
 * source may legitimately have no pages yet.
 */
class ExtensionContentRepository(
    private val registry: ExtensionSourceRegistry,
    /**
     * Persists a unit list. A function rather than the whole repository: this class needs
     * exactly one write, and depending on one operation keeps it testable without a mocking
     * framework. `LibraryRepository` is a final class over two DAOs.
     */
    private val persistUnits: suspend (entryId: String, units: List<LibraryUnitEntity>) -> Unit,
    /**
     * Diagnostics sink, defaulting to `android.util.Log`.
     *
     * A seam because `Log` throws in plain JVM tests, and the failures logged here — a source
     * that vanished, a refresh that failed — are silent to the user by design. Injecting it
     * keeps these tests on the JVM rather than moving them to Robolectric.
     */
    private val log: (String, String) -> Unit = { tag, message -> Log.w(tag, message) },
) {

    /**
     * Finds the extension source backing [entry].
     *
     * Matched on `sourceId`, which is Aniyomi's MD5-derived id and stable across renames.
     * Matching on the source *name* instead would break whenever a source is renamed — and a
     * source renaming would otherwise silently detach every library entry using it.
     */
    fun resolveSource(entry: LibraryEntryEntity): com.auris.app.source.MediaSource? {
        val loaded = registry.sources.firstOrNull { it.aniyomiId.toString() == entry.sourceId }
            ?: return null

        // `sourceId` identifies the source, not how it should be presented; the entry's own
        // media type decides that, so a manga read through an anime-shaped source still works.
        return runCatching { loaded.toMediaSource(entry.mediaType) }.getOrNull()
    }

    /**
     * Fetches episodes/chapters for [entry] from its extension.
     *
     * @return the units, or a failure explaining why none are available. An empty list is a
     *   legitimate result — a freshly aired show with no episodes listed yet.
     */
    suspend fun fetchUnits(entry: LibraryEntryEntity): Result<List<SourceUnit>> {
        val source = resolveSource(entry)
            ?: return Result.failure(
                UnavailableException("Source ${entry.sourceId} is not installed"),
            )

        return runCatching {
            source.units(entry.toSourceItem())
        }.onFailure {
            log(TAG, "Unit fetch failed for ${entry.id}: ${it.message}")
        }
    }

    /**
     * Fetches units and persists them to Room.
     *
     * Persisting is what makes the list appear instantly on the next open and survive an
     * offline launch; the network fetch then acts as a refresh. Units are only written when the
     * fetch actually returned something, so a transient failure cannot overwrite a good stored
     * list with nothing.
     */
    suspend fun refreshUnits(entry: LibraryEntryEntity): Result<List<SourceUnit>> {
        val result = fetchUnits(entry)
        result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { units ->
            runCatching {
                persistUnits(entry.id, units.map { it.toEntity(entry) })
            }.onFailure {
                log(TAG, "Could not persist units for ${entry.id}: ${it.message}")
            }
        }
        return result
    }

    /**
     * Resolves playable targets for one unit.
     *
     * Empty for manga, which exposes page images rather than a stream — matching
     * `MediaSource.streams`, so a caller cannot mistake "no stream" for "not fetched".
     */
    suspend fun fetchStreams(entry: LibraryEntryEntity, unit: SourceUnit): Result<List<StreamTarget>> {
        val source = resolveSource(entry)
            ?: return Result.failure(UnavailableException("Source ${entry.sourceId} is not installed"))

        return runCatching { source.streams(entry.toSourceItem(), unit) }
    }

    /**
     * Fetches the page images for one chapter, in reading order.
     *
     * Pages are resolved on every open rather than cached: image URLs on manga sites are
     * frequently signed or session-scoped and expire within minutes, so a cached URL would be
     * worse than no cache. Coil's own disk cache handles repeat views of the same chapter
     * without the extension being involved again.
     */
    suspend fun fetchPages(entry: LibraryEntryEntity, unit: SourceUnit): Result<List<Page>> {
        val source = resolveSource(entry)
            ?: return Result.failure(UnavailableException("Source ${entry.sourceId} is not installed"))

        val reader = source as? com.auris.app.source.MangaReaderSource
            ?: return Result.failure(
                UnavailableException("Source ${entry.sourceId} does not expose chapter pages"),
            )

        return runCatching { reader.pages(entry.toSourceItem(), unit) }
            .onFailure { log(TAG, "Page fetch failed for ${entry.id}/${unit.index}: ${it.message}") }
    }

    /** Builds the minimal [SourceItem] the adapter needs to call back into a source. */
    private fun LibraryEntryEntity.toSourceItem() = SourceItem(
        url = sourceKey,
        title = title,
        thumbnailUrl = artworkUrl,
        author = author,
    )

    private fun SourceUnit.toEntity(entry: LibraryEntryEntity) = LibraryUnitEntity(
        entryId = entry.id,
        unitIndex = index,
        // Anikku's source-relative URL; stable, unlike a display title.
        sourceUnitId = url,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationMs = null,
        isWatched = false,
        lastPositionMs = 0L,
        // Int, not Boolean: the column is a tri-state marker (0/1 and a position-style value),
        // so 0 here means "no bookmark" rather than "false".
        bookmark = 0,
    )

    /** The extension backing this content is not available. */
    class UnavailableException(message: String) : Exception(message)

    private companion object {
        const val TAG = "AurisContent"
    }
}