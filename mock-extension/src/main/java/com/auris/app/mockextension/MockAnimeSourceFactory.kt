package com.auris.app.mockextension

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceFactory

/**
 * Factory discovered by the extension loader via reflection.
 *
 * A real extension's factory is referenced from `tachiyomi.extension.factory.<package>`
 * meta-data. The loader looks for implementations of [AnimeSourceFactory] / [SourceFactory]
 * on the classpath instead, so this needs no Aniyomi-only annotation to be found.
 *
 * Three sources are registered to cover the cases that matter:
 *  - two anime sources differing only by language -> distinct stable ids;
 *  - one manga-shaped source -> proves manga loads through the anime hierarchy.
 */
class MockAnimeSourceFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> =
        listOf(MockAnimeSource(), MockAnimeAltSource())
}

/** Manga-shaped factory. Under Anikku this returns the same type as the anime one. */
class MockMangaSourceFactory : SourceFactory {
    override fun createSources(): List<CatalogueSource> = listOf(MockMangaViewSource())
}
