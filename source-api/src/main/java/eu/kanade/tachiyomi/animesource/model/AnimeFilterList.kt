package eu.kanade.tachiyomi.animesource.model

/**
 * Filter tree for anime sources.
 *
 * ### Why this is a real class and not a `typealias`
 * This was `typealias AnimeFilterList = FilterList`, which compiles and works perfectly for our
 * own Kotlin code — and is invisible to extensions.
 *
 * A Kotlin `typealias` generates **no JVM class**. All it emits is a file facade
 * (`AnimeFilterListKt`), so an extension dex compiled against a real
 * `eu.kanade.tachiyomi.animesource.model.AnimeFilterList` gets
 * `ClassNotFoundException` when it tries to link. This was found by loading a real Aniyomi
 * extension, not by a test: the mock extension is written against our own declarations and so
 * matched the typealias by construction.
 *
 * The constructor signature is fixed by the binary contract — extensions call
 * `AnimeFilterList(AnimeFilter, AnimeFilter, …)` with an *array*, i.e. a Kotlin `vararg`, and then
 * `isEmpty()` on it.
 */
class AnimeFilterList(vararg val list: AnimeFilter) : Iterable<AnimeFilter> {

    /** Convenience for callers that already hold a list. */
    constructor(list: List<AnimeFilter>) : this(*list.toTypedArray())

    /**
     * Whether the source offers no filters.
     *
     * The host calls this before showing a filter sheet, and extensions call it on the object
     * their own `getFilterList()` returned.
     */
    val isEmpty: Boolean get() = list.isEmpty()

    val isNotEmpty: Boolean get() = list.isNotEmpty()

    override fun iterator(): Iterator<AnimeFilter> = list.asIterable().iterator()

    val size: Int get() = list.size

    operator fun get(index: Int): AnimeFilter = list[index]

    override fun equals(other: Any?): Boolean = other is AnimeFilterList && other.list == list

    override fun hashCode(): Int = list.hashCode()

    override fun toString(): String = "AnimeFilterList(${list.joinToString()})"
}
