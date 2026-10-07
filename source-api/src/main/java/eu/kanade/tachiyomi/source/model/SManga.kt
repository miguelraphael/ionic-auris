package eu.kanade.tachiyomi.source.model

/**
 * A manga is an anime.
 *
 * Anikku collapses the two hierarchies: `SManga` is a typealias for `SAnime`, and the app
 * renders an anime source as a manga source. Verified against `master`:
 * `typealias SManga = eu.kanade.tachiyomi.animesource.model.SAnime`.
 *
 * ### Why this matters to Auris
 * There is therefore **no separate manga source hierarchy** — a "manga extension" is an
 * `AnimeHttpSource` that returns manga-shaped data. An extension manager that keys sources
 * by `is MangaSource` will find nothing; the distinction is in how the UI presents a source,
 * not in what class implements it.
 */
typealias SManga = eu.kanade.tachiyomi.animesource.model.SAnime

/** `SManga` creation, mirroring the anime side. */
fun createSManga(): SManga = eu.kanade.tachiyomi.animesource.model.SAnime.create()

/** Manga status constants, kept as an alias of the anime values. */
object SMangaStatus {
    const val UNKNOWN = eu.kanade.tachiyomi.animesource.model.SAnime.UNKNOWN
    const val ONGOING = eu.kanade.tachiyomi.animesource.model.SAnime.ONGOING
    const val FINISHED = eu.kanade.tachiyomi.animesource.model.SAnime.FINISHED
    const val CANCELLED = eu.kanade.tachiyomi.animesource.model.SAnime.CANCELLED
    const val ON_HIATUS = eu.kanade.tachiyomi.animesource.model.SAnime.ON_HIATUS
}
