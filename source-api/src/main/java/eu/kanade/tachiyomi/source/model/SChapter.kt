package eu.kanade.tachiyomi.source.model

/**
 * A chapter is an episode.
 *
 * Anikku: `typealias SChapter = eu.kanade.tachiyomi.animesource.model.SEpisode`.
 *
 * `chapter_number` is available on [SEpisode] as an alias property for `episode_number`, so
 * manga-shaped sources that read or write it keep working unchanged.
 */
typealias SChapter = eu.kanade.tachiyomi.animesource.model.SEpisode

fun createSChapter(): SChapter = eu.kanade.tachiyomi.animesource.model.SEpisode.create()

/** One image in a chapter. */
data class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
) {
    val number: Int get() = index + 1
}
