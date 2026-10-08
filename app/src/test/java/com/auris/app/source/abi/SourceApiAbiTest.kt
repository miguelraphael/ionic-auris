package com.auris.app.source.abi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Locks the **binary** shape of `:source-api` — the JVM descriptors extensions were compiled
 * against.
 *
 * ### Why declarations are not enough
 * Every one of the five breaks that stopped real extensions from loading was invisible to a
 * check that only asserted "this class exists":
 *
 * | Break | Why a declaration check missed it |
 * | --- | --- |
 * | `AnimeFilterList` was a `typealias` | The Kotlin declaration was present; only the emitted **JVM class** was missing |
 * | `state` typed `Boolean`/`Int` | Compiles to `()Z`/`()I`, but extensions call `getState()()Ljava/lang/Object;` |
 * | `NetworkHelper` was an `interface` | Existed, but extensions invoke it with `invokevirtual` |
 * | `ParsedAnimeHttpSource.network` absent | No declaration existed to notice |
 * | `ConfigurableAnimeSource` absent | Same |
 *
 * All five presented identically to a user: the extension loads and registers **zero sources**.
 *
 * ### How the contract is pinned
 * Signatures are written as **descriptor strings** — the same text a JVM would match against —
 * rather than as Kotlin signatures, because the descriptor is what linking actually uses. A
 * change that keeps the Kotlin API compiling but alters a descriptor fails here.
 *
 * The expected values were read out of real published extension dex, not from our own sources,
 * which is the whole point: our sources are the thing under test.
 */
class SourceApiAbiTest {

    // ---- Helpers ------------------------------------------------------------

    private fun requireClass(name: String): Class<*> {
        val resolved: Class<*>? = try {
            Class.forName(name)
        } catch (e: ClassNotFoundException) {
            null
        }
        // `checkNotNull` rather than an elvis: `?: fail(...)` widens the inferred type to `Any`
        // because Kotlin cannot use `Nothing` to refine a nullable here.
        return checkNotNull(resolved) {
            "`:source-api` does not emit `$name` as a JVM class. A Kotlin `typealias` produces " +
                "no class, so extensions compiled against it cannot link — this is exactly how " +
                "`AnimeFilterList` broke every real extension."
        }
    }

    /**
     * Asserts [clazz] declares exactly the method named [descriptor].
     *
     * [descriptor] is written as `name(paramTypes):returnType` using JVM type names, e.g.
     * `getState():Ljava/lang/Object;`.
     */
    /**
     * Whether [clazz] actually provides [descriptor], constructors and inherited members included.
     *
     * One predicate for both the individual assertions and the summary, so the summary can never
     * drift from the checks it summarises.
     */
    private fun provides(clazz: Class<*>, descriptor: String): Boolean {
        val parsed = Descriptor.parse(descriptor)
        if (parsed.name == "<init>") {
            return try {
                clazz.getDeclaredConstructor(*parsed.parameterTypes); true
            } catch (e: NoSuchMethodException) {
                false
            }
        }
        return findMethod(clazz, parsed)?.returnType == parsed.returnType
    }

    private fun assertDescriptor(clazz: Class<*>, descriptor: String) {
        val parsed = Descriptor.parse(descriptor)

        // `<init>` is a constructor, and reflection keeps it in a separate lookup — asking
        // `getDeclaredMethod` for `<init>` always misses, which would report a present
        // constructor as absent.
        if (parsed.name == "<init>") {
            val constructor = try {
                clazz.getDeclaredConstructor(*parsed.parameterTypes)
            } catch (e: NoSuchMethodException) {
                fail(
                    "${clazz.name} does not declare the constructor `$descriptor`. Found " +
                        "instead: ${describeConstructors(clazz)}",
                )
                return
            }
            assertNotNull(constructor)
            return
        }

        val method = findMethod(clazz, parsed)
            ?: run {
                fail(
                    "${clazz.name} does not provide `$descriptor`. Found instead: " +
                        describe(clazz),
                )
                return
            }

        if (parsed.protected) {
            assertTrue(
                "`$descriptor` must be protected — it is for subclasses, not host UI",
                Modifier.isProtected(method!!.modifiers),
            )
        }
    }

    /**
     * Finds a method on [clazz] **or any superclass**.
     *
     * ### Why the hierarchy matters
     * `AnimeFilter.CheckBox` does not declare `getName()` — it inherits it from the sealed base,
     * and the dex proves that is fine: real extensions call
     * `invoke-virtual AnimeFilter$CheckBox;.getName`, which dalvik resolves up the hierarchy.
     *
     * Using `getDeclaredMethod` would report a perfectly good API as broken and train whoever
     * maintains this next to "fix" it by re-declaring members on subclasses. Resolution follows
     * the JVM's rules, so this does too.
     */
    private fun findMethod(clazz: Class<*>, parsed: Descriptor): Method? =
        findMethodAnywhere(clazz, parsed)

    /**
     * Locates a method by name and parameter types.
     *
     * ### The return type is checked by the caller, and that matters
     * `Class.getDeclaredMethod` matches on **name and parameter types only** — it ignores the
     * return type entirely. A guard that trusted it would report `getState()Z` as satisfying a
     * `getState()Ljava/lang/Object;` expectation, and would therefore silently pass on precisely
     * the drift it was written to catch: narrowing a property from `Any?` to `Boolean` changes
     * the descriptor while leaving the Kotlin declaration compiling and present.
     *
     * [provides] therefore compares `returnType` itself. `SourceApiAbiGuardTest` pins this, by
     * asserting a deliberately narrowed type is *rejected*.
     */
    private fun findMethodAnywhere(clazz: Class<*>, parsed: Descriptor): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(parsed.name, *parsed.parameterTypes)
            } catch (e: NoSuchMethodException) {
                current = current.superclass
            }
        }
        // Interfaces too: a default method may live on one.
        current = clazz
        while (current != null) {
            for (candidate in current.interfaces) {
                try {
                    return candidate.getDeclaredMethod(parsed.name, *parsed.parameterTypes)
                } catch (e: NoSuchMethodException) {
                    findMethod(candidate, parsed)?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    /** Constructors, in descriptor form, for failure messages. */
    private fun describeConstructors(clazz: Class<*>): String = clazz.declaredConstructors
        .joinToString { "<init>(${it.parameterTypes.joinToString(",") { type -> type.name }}):void" }
        .ifEmpty { "(no constructors)" }

    private fun assertNotInterface(clazz: Class<*>, why: String) {
        // A call site compiled with `invokevirtual` against an interface is rejected at runtime
        // with IncompatibleClassChangeError, even though every member resolves.
        assertTrue(
            "${clazz.name} must be a class, not an interface: $why",
            !clazz.isInterface,
        )
    }

    private fun describe(clazz: Class<*>): String = clazz.declaredMethods
        .joinToString { Descriptor.of(it) }
        .ifEmpty { "(no declared methods)" }

    // ---- The types real extensions link against -----------------------------

    @Test
    fun animeFilterTypesExistAsRealClasses() {
        requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilter")
        requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilterList")
    }

    @Test
    fun animeFilterListAcceptsAVarargOfFilters() {
        // Extensions build an `AnimeFilter[]` and hand it over. A `List` parameter would emit
        // `(Ljava/util/List;)V` and not match.
        assertDescriptor(
            requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilterList"),
            "<init>([Leu/kanade/tachiyomi/animesource/model/AnimeFilter;)V",
        )
    }

    @Test
    fun animeFilterListExposesIsEmpty() {
        assertDescriptor(
            requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilterList"),
            "isEmpty()Z",
        )
    }

    @Test
    fun filterGroupTakesAListAndReturnsAnObject() {
        val group = requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilter\$Group")
        assertDescriptor(group, "<init>(Ljava/lang/String;Ljava/util/List;)V")
        // Object, not List: every `getState` in the real dex returns Ljava/lang/Object.
        assertDescriptor(group, "getState()Ljava/lang/Object;")
    }

    @Test
    fun filterCheckBoxTakesAStringAndBoolean() {
        val checkBox = requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilter\$CheckBox")
        assertDescriptor(checkBox, "<init>(Ljava/lang/String;Z)V")
        assertDescriptor(checkBox, "getName()Ljava/lang/String;")
        assertDescriptor(checkBox, "getState()Ljava/lang/Object;")
    }

    @Test
    fun filterSelectTakesAStringAnObjectArrayAndAnInt() {
        val select = requireClass("eu.kanade.tachiyomi.animesource.model.AnimeFilter\$Select")
        // The array, not a List — the single easiest signature to get wrong here.
        assertDescriptor(select, "<init>(Ljava/lang/String;[Ljava/lang/Object;I)V")
        assertDescriptor(select, "getState()Ljava/lang/Object;")
    }

    @Test
    fun configurableAnimeSourceTakesAPreferenceScreen() {
        assertDescriptor(
            requireClass("eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource"),
            "setupPreferenceScreen(Landroidx/preference/PreferenceScreen;)V",
        )
    }

    @Test
    fun networkHelperIsAClassNotAnInterface() {
        val helper = requireClass("eu.kanade.tachiyomi.network.NetworkHelper")

        assertNotInterface(
            helper,
            "extensions invoke its members with invokevirtual, which dalvik rejects against " +
                "an interface with IncompatibleClassChangeError",
        )
        assertDescriptor(helper, "getClient()Lokhttp3/OkHttpClient;")
    }

    @Test
    fun parsedHttpSourceExposesNetworkToSubclasses() {
        val parsed = requireClass("eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource")

        assertDescriptor(parsed, "getNetwork()Leu/kanade/tachiyomi/network/NetworkHelper;")
    }

    @Test
    fun animeSourceDeclaresTheCatalogueContract() {
        // The methods an extension implements. If any is renamed or re-typed, the extension
        // class no longer satisfies the interface and every source in the APK is discarded.
        val animeSource = requireClass("eu.kanade.tachiyomi.animesource.AnimeSource")

        assertDescriptor(animeSource, "getName()Ljava/lang/String;")
        assertDescriptor(animeSource, "getLang()Ljava/lang/String;")
        assertDescriptor(animeSource, "getId()J")
    }

    @Test
    fun animeCatalogueSourceDeclaresTheFilterEntryPoints() {
        val catalogue =
            requireClass("eu.kanade.tachiyomi.animesource.AnimeCatalogueSource")

        assertDescriptor(
            catalogue,
            "getFilterList()Leu/kanade/tachiyomi/animesource/model/AnimeFilterList;",
        )
        assertDescriptor(catalogue, "getSupportsLatest()Z")
    }

    @Test
    fun theMangaPageTypesAreRealClasses() {
        // `Hoster` and `Video` are what `AnimeSourceAdapter.pages()` reads. A typealias here would
        // make every manga source unloadable in the same way `AnimeFilterList` was.
        requireClass("eu.kanade.tachiyomi.animesource.model.Hoster")
        requireClass("eu.kanade.tachiyomi.animesource.model.Video")
        requireClass("eu.kanade.tachiyomi.animesource.model.SAnime")
        requireClass("eu.kanade.tachiyomi.animesource.model.SEpisode")
    }

    @Test
    fun sourceFactoryInterfaceIsStable() {
        val factory = requireClass("eu.kanade.tachiyomi.animesource.AnimeSourceFactory")

        assertTrue(
            "AnimeSourceFactory must be an interface; the loader discovers factories by type",
            factory.isInterface,
        )
        assertDescriptor(factory, "createSources()Ljava/util/List;")
    }

    /**
     * Every descriptor above, in one place, so a regression names itself.
     *
     * Failing this single test with the full list is more useful than five failures that each
     * show one missing member: it answers "what does the host actually look like now?" in a
     * single run.
     */
    @Test
    fun summaryOfTheContract() {
        val expected = listOf(
            "animesource.model.AnimeFilterList" to listOf(
                "<init>([Leu/kanade/tachiyomi/animesource/model/AnimeFilter;)V",
                "isEmpty()Z",
            ),
            "animesource.model.AnimeFilter\$Group" to listOf(
                "<init>(Ljava/lang/String;Ljava/util/List;)V",
                "getState()Ljava/lang/Object;",
            ),
            "animesource.model.AnimeFilter\$CheckBox" to listOf(
                "<init>(Ljava/lang/String;Z)V",
                "getName()Ljava/lang/String;",
                "getState()Ljava/lang/Object;",
            ),
            "animesource.model.AnimeFilter\$Select" to listOf(
                "<init>(Ljava/lang/String;[Ljava/lang/Object;I)V",
                "getState()Ljava/lang/Object;",
            ),
            "animesource.ConfigurableAnimeSource" to listOf(
                "setupPreferenceScreen(Landroidx/preference/PreferenceScreen;)V",
            ),
            "network.NetworkHelper" to listOf("getClient()Lokhttp3/OkHttpClient;"),
            "animesource.online.ParsedAnimeHttpSource" to listOf(
                "getNetwork()Leu/kanade/tachiyomi/network/NetworkHelper;",
            ),
        )

        val problems = mutableListOf<String>()

        expected.forEach { (path, descriptors) ->
            val clazz = try {
                Class.forName("eu.kanade.tachiyomi.$path")
            } catch (e: ClassNotFoundException) {
                problems += "MISSING CLASS eu.kanade.tachiyomi.$path (${e.message})"
                return@forEach
            }

            descriptors.forEach { descriptor ->
                if (!provides(clazz, descriptor)) {
                    problems += "eu.kanade.tachiyomi.$path is missing `$descriptor`; " +
                        "has: ${describe(clazz)}"
                }
            }
        }

        if (problems.isNotEmpty()) {
            fail(
                "`:source-api` no longer matches the ABI real extensions were compiled " +
                    "against. Each of these presents to a user as an extension that loads and " +
                    "then registers zero sources:\n" + problems.joinToString("\n") { " - $it" },
            )
        }

        assertEquals("contract should be satisfied", 0, problems.size)
    }
}
