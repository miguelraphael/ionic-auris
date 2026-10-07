package eu.kanade.tachiyomi.source.model

/**
 * Filters exposed by a source's advanced search.
 *
 * Vendored as a structural hierarchy rather than Aniyomi's full sealed class set: sources
 * construct `FilterList` from concrete filters, and the host only needs to read the tree to
 * render a filter sheet. Unknown filter kinds degrade to their display name.
 */
data class FilterList(val filters: List<Any> = emptyList()) {

    constructor(vararg filters: Any) : this(filters.toList())

    val isEmpty: Boolean get() = filters.isEmpty()
}

sealed class Filter(val name: String) {
    class Header(name: String) : Filter(name)
    class Separator(name: String) : Filter(name)

    /** A single choice list. */
    class Select(
        name: String,
        val options: List<Any>,
        var state: Int = 0,
    ) : Filter(name)

    /** Free text input. */
    class TextField(name: String, var state: String = "") : Filter(name)

    /** Tri-state checkbox. */
    class CheckBox(name: String, var state: Boolean = false) : Filter(name)

    /** Sort order selector, exposed separately so the UI can pin it to the top. */
    class Sort(
        name: String,
        val options: List<Any>,
        var state: Selection? = null,
    ) : Filter(name) {
        class Selection(val selected: Int, val isAscending: Boolean)
    }
}