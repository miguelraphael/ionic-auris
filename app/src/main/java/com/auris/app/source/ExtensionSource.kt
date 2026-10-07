package com.auris.app.source

import com.auris.app.core.model.MediaType
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.CatalogueSource

/**
 * A source loaded from an extension APK.
 *
 * ### No anime/manga union — deliberately
 * An earlier version of this file modelled two sealed branches,
 * `LoadedAnimeSource` / `LoadedMangaSource`, on the assumption that Aniyomi keeps separate
 * anime and manga hierarchies. **Anikku does not**: every manga type is a `typealias` for its
 * anime counterpart (`SManga = SAnime`, `HttpSource = AnimeHttpSource`, …), verified against
 * `master`. A source is therefore *one* `AnimeSource`, and "is this manga?" is a property of
 * how it is presented, not of what class implements it.
 *
 * Keeping the split would have meant every Anikku extension was misclassified, because none
 * of them implements a `MangaSource` that could be detected.
 */
data class LoadedSource(
    val source: AnimeSource,
    val packageName: String,
) {
    /** Aniyomi's stable id; feeds `library_entries.source_key`. */
    val aniyomiId: Long get() = source.id
    val sourceName: String get() = source.name
    val sourceLang: String get() = source.lang
    val baseUrl: String get() = (source as? eu.kanade.tachiyomi.animesource.online.AnimeHttpSource)?.baseUrl.orEmpty()
}

/** Manga-shaped sources are anime sources; this alias keeps call sites readable. */
typealias LoadedCatalogueSource = LoadedSource

/**
 * Wraps this source as an Auris [MediaSource].
 *
 * @param mediaType how Auris should present it. Every extension source is an `AnimeSource`
 *   under Anikku, so this is a caller decision rather than something inferred from the class.
 */
fun LoadedSource.toMediaSource(mediaType: MediaType = MediaType.ANIME): MediaSource =
    AnimeSourceAdapter(source, packageName, mediaType)

/** Convenience for callers holding a bare `AnimeSource`. */
fun AnimeSource.toLoadedSource(packageName: String): LoadedSource = LoadedSource(this, packageName)
