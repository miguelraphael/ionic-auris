package eu.kanade.tachiyomi.source.online

/**
 * An HTTP manga source is an HTTP anime source.
 *
 * Anikku: `typealias HttpSource = eu.kanade.tachiyomi.animesource.online.AnimeHttpSource`.
 *
 * This is the single most consequential structural fact about the Anikku ecosystem: there
 * is one source hierarchy, and "manga" is a presentation choice. An extension manager must
 * not look for a `HttpSource` that extends something separate — the classes are the same.
 */
typealias HttpSource = eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

/** Parsed-selector base class, likewise unified. */
typealias ParsedHttpSource = eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource

/**
 * Resolver base class, likewise unified.
 *
 * Anikku: `typealias ResolvableSource = ResolvableAnimeSource`.
 *
 * Manga-shaped helper it exposes:
 * `suspend fun ResolvableSource.getManga(uri: String): SManga? = getAnime(uri)`
 */
typealias ResolvableSource = eu.kanade.tachiyomi.animesource.online.ResolvableAnimeSource

/** Manga-shaped helper: Anikku maps this onto `getAnimeUrl`. */
@Suppress("FunctionName", "UnusedReceiverParameter")
fun HttpSource.getMangaUrl(manga: eu.kanade.tachiyomi.source.model.SManga): String =
    getAnimeUrl(manga)

/** Manga-shaped helper: Anikku maps this onto `prepareNewEpisode`. */
@Suppress("FunctionName", "UnusedReceiverParameter")
fun HttpSource.prepareNewChapter(
    chapter: eu.kanade.tachiyomi.source.model.SChapter,
    manga: eu.kanade.tachiyomi.source.model.SManga,
) = prepareNewEpisode(chapter, manga)