package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * A browsable catalogue source.
 *
 * Anikku collapses this into the anime hierarchy:
 * `typealias CatalogueSource = eu.kanade.tachiyomi.animesource.AnimeCatalogueSource`.
 * Kept as an alias (not a duplicate interface) so an extension compiled against either
 * spelling is the same type at runtime.
 */
typealias CatalogueSource = eu.kanade.tachiyomi.animesource.AnimeCatalogueSource

/** Creates the sources provided by an extension, one instance per configuration. */
interface SourceFactory {
    fun createSources(): List<CatalogueSource>
}

/** An alias of [CatalogueSource], matching Anikku's `Source`. */
typealias Source = eu.kanade.tachiyomi.animesource.AnimeSource

/** Manga is an anime; the app decides how to present one. */
typealias MangaSource = eu.kanade.tachiyomi.animesource.AnimeSource

// ---- Manga-shaped conveniences ------------------------------------------------
//
// Anikku exposes these as extension functions on the anime interface rather than as
// members, so a manga source is "an anime source you ask the manga questions".

suspend fun AnimeCatalogueSource.getPopularManga(page: Int): MangasPage = getPopularAnime(page)

suspend fun AnimeCatalogueSource.getSearchManga(
    page: Int,
    query: String,
    filters: AnimeFilterList,
): MangasPage = getSearchAnime(page, query, filters)

@Suppress("UNUSED_PARAMETER")
suspend fun Source.getMangaDetails(manga: SManga): SManga = getAnimeDetails(manga)

@Suppress("UNUSED_PARAMETER")
suspend fun Source.getChapterList(manga: SManga): List<SChapter> = getEpisodeList(manga)