package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Filter tree for anime sources.
 *
 * Alias of the manga [FilterList]: the two hierarchies are structurally identical in
 * Aniyomi too, and keeping one implementation means the filter sheet is written once.
 */
typealias AnimeFilterList = FilterList

@Suppress("unused")
private typealias UnusedFilterAlias = Filter