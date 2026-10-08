package com.auris.app.source

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Converts the host's internal filter tree into the shape extensions are compiled against.
 *
 * ### Why a conversion exists at all
 * The app speaks two filter vocabularies, and they stopped being interchangeable.
 *
 * `eu.kanade.tachiyomi.source.model.FilterList` is **ours**: a deliberately loose container the
 * host uses to describe a filter sheet without committing to a UI. `AnimeFilterList` is
 * **Anikku's**: the concrete hierarchy a real extension returns from `getFilterList()` and
 * expects back in `getSearchAnime`.
 *
 * They used to be the same type, because `AnimeFilterList` was a `typealias` for `FilterList`.
 * That compiled fine and was invisible until a real extension was loaded — a `typealias` emits no
 * JVM class, so extension dex could not link it. `AnimeFilterList` is now a real class again, so
 * the two types are distinct and a conversion is genuinely required.
 *
 * ### Why it is not a cast
 * The hierarchies genuinely differ — `Filter.Select` holds `List<Any>` where `AnimeFilter.Select`
 * holds `Array<Any>`, and Anikku has a `Group` that ours does not. A cast would compile and then
 * fail at the first filter a user touched.
 */
fun FilterList.toAnimeFilterList(): AnimeFilterList = AnimeFilterList(
    filters.mapNotNull { it.toAnimeFilter() },
)

/**
 * Converts one filter, returning null for kinds Anikku has no equivalent for.
 *
 * Dropping an unrepresentable filter rather than substituting a placeholder: the alternative is
 * showing a control the source will ignore, which is worse than omitting it, because the user
 * believes they filtered by something.
 */
private fun Any.toAnimeFilter(): AnimeFilter? = when (this) {
    is Filter.CheckBox -> AnimeFilter.CheckBox(name, state)

    is Filter.Select -> AnimeFilter.Select(name, options.toTypedArray(), state)

    is Filter.TextField -> AnimeFilter.TextField(name, state)

    // `Sort` is host-only; Anikku models it as an ordinary Select, so it converts rather than
    // being dropped. `Header` and `Separator` are presentational with no Anikku equivalent and
    // are dropped — the host renders its own section titles.
    is Filter.Sort -> AnimeFilter.Select(name, options.toTypedArray(), state?.selected ?: 0)

    else -> null
}
