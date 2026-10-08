package eu.kanade.tachiyomi.animesource.model

/**
 * One entry in an anime source's advanced-search filter tree.
 *
 * ### Shape is dictated by the binary, not by taste
 * Member names, arities and **return types** were read out of a real published extension's dex
 * rather than guessed, because Kotlin's property types determine the JVM method descriptor and a
 * mismatch is a `NoSuchMethodError` at the moment a user opens the filter sheet — not a compile
 * error on our side.
 *
 * Verified constructors and accessors:
 * ```
 * AnimeFilter$Group.<init>:(Ljava/lang/String;Ljava/util/List;)V   .getState:()Ljava/lang/Object;
 * AnimeFilter$CheckBox.<init>:(Ljava/lang/String;Z)V                .getName:()Ljava/lang/String;
 * AnimeFilter$CheckBox.<init>:(Ljava/lang/String;Z;Lkotlin/jvm/internal/DefaultConstructorMarker;)V
 * AnimeFilter$CheckBox.getState:()Ljava/lang/Object;
 * AnimeFilter$Select.<init>:(Ljava/lang/String;[Ljava/lang/Object;I)V  .getState:()Ljava/lang/Object;
 * ```
 *
 * ### Why `state` is `Any?` on every subtype
 * All three accessors return `Ljava/lang/Object;`, not `()Z` or `()I`. A Kotlin property typed
 * `Boolean` compiles to `getState()Z` and one typed `Int` to `()I`, so declaring the state with
 * its natural type would produce descriptors that do **not** match and would fail to link at
 * exactly the wrong moment.
 *
 * Declaring `state: Any?` yields `getState()Ljava/lang/Object;` for Boolean, Int and List alike.
 * The typed accessors ([booleanState], [intState]) exist so host code is not reduced to casting.
 */
sealed class AnimeFilter(val name: String) {

    /** The current value. See the class note on why this is `Any?`. */
    abstract val state: Any?

    /** Whether this is a [Group] and so should be expanded rather than drawn as a control. */
    val isGroup: Boolean get() = this is Group

    // ---- Typed reads for host code ------------------------------------------

    /** The state as a [Boolean], or null when this filter is not a checkbox. */
    val booleanState: Boolean? get() = state as? Boolean

    /** The state as an [Int], or null when this filter is not a select. */
    val intState: Int? get() = (state as? Number)?.toInt()

    /**
     * A single-choice list.
     *
     * [options] is an `Array`, not a `List`, purely to match the descriptor extensions were
     * compiled against: `Select.<init>:(Ljava/lang/String;[Ljava/lang/Object;I)V`. A `List`
     * parameter would compile to `(String, List, int)` and fail to link.
     */
    class Select(
        name: String,
        options: Array<Any>,
        selected: Int = 0,
    ) : AnimeFilter(name) {

        constructor(name: String, options: List<Any>, selected: Int = 0) :
            this(name, options.toTypedArray(), selected)

        val options: List<Any> = options.toList()
        /** The selected option index. Mutable so a filter sheet can drive it. */
        var selectedIndex: Int = selected
            set(value) {
                // An out-of-range index would make every later read of [options] throw
                // IndexOutOfBounds from the UI thread. Clamping keeps a corrupt or stale
                // persisted selection from crashing the filter sheet.
                field = if (options.isEmpty()) 0 else value.coerceIn(0, options.lastIndex)
            }

        override val state: Any? get() = selectedIndex

        val selectedOption: Any? get() = options.getOrNull(selectedIndex)
    }

    /** Tri-state checkbox, carrying a [Boolean]. */
    class CheckBox(name: String, var checked: Boolean = false) : AnimeFilter(name) {
        override val state: Any? get() = checked
    }

    /**
     * A collapsible group of filters; its "state" is the list of children it holds.
     *
     * [state] is declared as `Any?` rather than `List<*>` purely so the accessor descriptor is
     * `()Ljava/lang/Object;`, matching what extensions were compiled against. Use [children].
     */
    class Group(name: String, children: List<*>) : AnimeFilter(name) {

        /**
         * The nested filters.
         *
         * Kept as `List<*>` rather than `List<AnimeFilter>` because Anikku's groups are not
         * necessarily all filters, and narrowing here would reject a valid construction with a
         * cast exception at load time.
         */
        val children: List<*> = children

        /** Typed view for host code that wants the filters. */
        val filters: List<AnimeFilter>
            get() = children.filterIsInstance<AnimeFilter>()

        // `Any?` rather than the list itself, so the accessor is `()Ljava/lang/Object;`.
        override val state: Any? get() = children
    }

    /** Free text input. */
    class TextField(name: String, var text: String = "") : AnimeFilter(name) {
        override val state: Any? get() = text
    }

    override fun toString(): String = "${javaClass.simpleName}($name=$state)"
}
