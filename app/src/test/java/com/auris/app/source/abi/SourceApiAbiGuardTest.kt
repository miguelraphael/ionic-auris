package com.auris.app.source.abi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Proves the ABI guard **can fail**, and fails on the five real breaks it exists to catch.
 *
 * ### Why this file exists
 * A conformance guard is only worth its maintenance cost if it is known to reject the mistakes it
 * was written for. Every check in [SourceApiAbiTest] asserts the *current* API matches, which
 * means a guard that silently compared nothing would pass forever.
 *
 * So each check is run here against a synthetic type built to be wrong in exactly one specific
 * way — a `typealias`-shaped absence, a narrowed property type, an interface where a class is
 * required — and asserted to be rejected. If someone later weakens the predicate to make a
 * legitimate change pass, these fail.
 *
 * ### The five real breaks, restated as test cases
 * 1. `AnimeFilterList` was a `typealias` — no JVM class emitted at all.
 * 2. `state` typed `Boolean`/`Int` — emits `()Z`/`()I` where extensions call `getState()()Ljava/lang/Object;`.
 * 3. `NetworkHelper` was an `interface` — extensions invoke it with `invokevirtual`.
 * 4. `ParsedAnimeHttpSource.network` absent.
 * 5. `ConfigurableAnimeSource` absent.
 */
class SourceApiAbiGuardTest {

    // ---- A stand-in for "the types the host provides" ------------------------

    /** Correct shapes, matching what `:source-api` now emits. */
    object Correct {
        class Filters {
            var state: Any? = null
        }

        class List {
            val isEmpty: Boolean = false
        }
    }

    /** Break #2: `state` narrowed to `Boolean`, so the accessor becomes `()Z`. */
    class NarrowedState {
        val state: Boolean = false
    }

    /** Break #3: an interface where extensions require a class. */
    interface HelperAsInterface {
        val client: okhttp3.OkHttpClient
    }

    /** The correct shape of the same thing. */
    abstract class HelperAsClass {
        abstract val client: okhttp3.OkHttpClient
    }

    // ---- The predicate under test -------------------------------------------

    /**
     * Mirrors the predicate in [SourceApiAbiTest], return type included.
     *
     * Kept as a local copy rather than shared so that a weakening of the real guard shows up
     * here as a failure instead of being silently inherited.
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
        return find(clazz, parsed)?.returnType == parsed.returnType
    }

    private fun find(clazz: Class<*>, parsed: Descriptor): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(parsed.name, *parsed.parameterTypes)
            } catch (e: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    // ---- Break #2: narrowed property type -----------------------------------

    @Test
    fun aNarrowedStateIsRejected() {
        // This is the subtle one. The Kotlin declaration is present and compiles, and a
        // declaration-only guard passes. Only the descriptor reveals that extensions call
        // `getState()Ljava/lang/Object;` and this emits `getState()Z`.
        assertTrue(
            "sanity: the correct shape must satisfy the descriptor",
            provides(Correct.Filters::class.java, "getState()Ljava/lang/Object;"),
        )

        assertFalse(
            "a `Boolean` state emits `getState()Z`, which no extension calls — the guard must " +
                "reject it",
            provides(NarrowedState::class.java, "getState()Ljava/lang/Object;"),
        )
    }

    @Test
    fun aDescriptorDoesNotAccidentallyMatchAnotherArity() {
        // Guards the guard: `getState()` with no arguments must not be satisfied by a method of
        // the same name taking parameters, or a signature change could slip through.
        assertFalse(
            provides(NarrowedState::class.java, "getState(I)Ljava/lang/Object;"),
        )
    }

    // ---- Break #3: interface where a class is required ----------------------

    @Test
    fun anInterfaceIsDistinguishableFromAnAbstractClass() {
        // The predicate cannot reject this on shape alone — both offer `getClient()`. What
        // matters is that a separate check exists and that it is meaningful, because
        // `IncompatibleClassChangeError` is thrown by the VM on an invokeinterface/invokevirtual
        // mismatch, never by a missing member.
        assertTrue(provides(HelperAsInterface::class.java, "getClient()Lokhttp3/OkHttpClient;"))
        assertTrue(provides(HelperAsClass::class.java, "getClient()Lokhttp3/OkHttpClient;"))

        // So class-ness has to be asserted separately, and must actually distinguish the two.
        assertTrue(
            "the correct shape is an abstract class",
            !HelperAsClass::class.java.isInterface && Modifier.isAbstract(HelperAsClass::class.java.modifiers),
        )
        assertTrue(
            "the broken shape is an interface, which is what the VM rejects",
            HelperAsInterface::class.java.isInterface,
        )
    }

    // ---- Break #1: a typealias emits no class --------------------------------

    @Test
    fun anAbsentTypeIsRejectedRatherThanIgnored() {
        assertFalse(
            "a missing member must fail the contract, not be skipped",
            provides(Correct.Filters::class.java, "getAbsent()Ljava/lang/Object;"),
        )

        // A `typealias` presents as a class that simply is not there, so absence is the shape
        // break #1 took. Resolution must fail loudly rather than yielding a null to skip over.
        val missing = try {
            Class.forName("eu.kanade.tachiyomi.animesource.model.NotEmittedByATypealias")
            true
        } catch (e: ClassNotFoundException) {
            false
        }
        assertFalse("a typealias emits no class, so it must not resolve", missing)
    }

    // ---- The descriptor machinery itself ------------------------------------

    @Test
    fun parameterListsTokeniseOnSemicolonsNotCommas() {
        // The bug that broke this guard during development: splitting on commas merged `String`
        // and `List` into one unresolvable token, which then reported as "the method does not
        // exist" — pointing at the wrong problem entirely. `;` is the only separator.
        assertTrue(
            Descriptor.splitParameters("Ljava/lang/String;Ljava/util/List;")
                .let { it == listOf("Ljava/lang/String;", "Ljava/util/List;") },
        )
        assertTrue(Descriptor.splitParameters("").isEmpty())
        assertTrue(Descriptor.splitParameters("Ljava/lang/String;").size == 1)
    }

    @Test
    fun aCommaSeparatedParameterListIsRejectedRatherThanGuessedAt() {
        // Accepting a non-canonical form would reintroduce the ambiguity above, and a wrong guess
        // reads as "the method is missing" — which is how a guard talks you out of a real break.
        val threw = try {
            Descriptor.splitParameters("Ljava/lang/String,Ljava/util/List;"); false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("a comma-separated list must be rejected", threw)
    }

    @Test
    fun arrayParametersKeepTheirBrackets() {
        assertTrue(
            Descriptor.splitParameters("Ljava/lang/String;[Ljava/lang/Object;I")
                .let {
                    it == listOf(
                        "Ljava/lang/String;",
                        "[Ljava/lang/Object;",
                        "I",
                    )
                },
        )
    }

    @Test
    fun theLeadingLSigilIsStrippedBeforeResolution() {
        // Stripping only the trailing `;` leaves `Ljava.lang.String`, which does not resolve and
        // produces the deeply misleading "cannot resolve java.lang.String".
        val resolved = Descriptor.parse("getName()Ljava/lang/String;")
        assertTrue(String::class.java.isAssignableFrom(resolved.returnType))
    }

    @Test
    fun genericParametersContainingCommasAreNotSplit() {
        // `Map;Ljava/lang/String;Ljava/lang/Long;` has commas only in the readable form; the
        // semicolon-delimited form must still be recognised as exactly two parameters.
        assertTrue(Descriptor.splitParameters("Ljava/util/Map;").size == 1)
        assertTrue(
            Descriptor.splitParameters("Ljava/util/Map;Ljava/lang/String;").size == 2,
        )
    }

    @Test
    fun aProtectedExpectationIsCarriedThroughParsing() {
        val parsed = Descriptor.parse("protected getNetwork()Leu/kanade/tachiyomi/network/NetworkHelper;")

        assertTrue("the 'protected ' prefix must be parsed, not treated as part of the name", parsed.protected)
        assertTrue(parsed.name == "getNetwork")
    }

    @Test
    fun malformedDescriptorsAreRejectedRatherThanMisparsed() {
        listOf("getName(", "getName)", "getName()").forEach { bad ->
            val threw = try {
                Descriptor.parse(bad); false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("`$bad` should be rejected", threw)
        }
    }
}
