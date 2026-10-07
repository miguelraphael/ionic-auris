package eu.kanade.tachiyomi.source.model

/**
 * A page of catalogue results, manga-spelled.
 *
 * Anikku serves manga through the anime hierarchy, so this is an alias of
 * [eu.kanade.tachiyomi.animesource.model.AnimesPage] — whose `mangas` property already
 * aliases `animes`.
 */
typealias MangasPage = eu.kanade.tachiyomi.animesource.model.AnimesPage

fun createMangasPage(
    mangas: List<SManga>,
    hasNextPage: Boolean,
): MangasPage = eu.kanade.tachiyomi.animesource.model.AnimesPage(mangas, hasNextPage)
