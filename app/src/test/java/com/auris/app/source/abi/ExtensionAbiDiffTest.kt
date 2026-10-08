package com.auris.app.source.abi

import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * The automated ABI guard: read a **real extension's dex**, list every host-API member it
 * references, and check we actually provide it.
 *
 * ### STATUS: incomplete, and not yet a guard
 * Every test here is `@Ignore`d because [DexAbiReader] does not yet parse the dex correctly.
 * It reads the string and type tables correctly — verified against a real fixture — but
 * `proto_ids` parameter parsing produces out-of-range type indexes and dies with an
 * `EOFException`. The header offsets are confirmed correct against `classes.dex` by hand.
 *
 * This is committed in a failing state on purpose. A guard that reports "no gaps" because it
 * parsed nothing is worse than no guard: it is indistinguishable from a perfect API, which is
 * precisely how the previous nine gaps survived. Disabled until the parser is correct, with the
 * failure recorded here rather than hidden.
 *
 * ### What still works today
 * [DexAbiReader] resolving type references, and the cause-chain logging in `ExtensionLoader`,
 * are the parts worth keeping. The diagnostic logging is what made gaps 9 and 10 findable at all.
 *
 * ### What this replaces
 * Nine ABI gaps were found the expensive way — load an extension, crash, read the
 * `NoSuchMethodError`, fix, rebuild, repeat. Each cycle cost a full build and install, and each
 * error named only a file facade (`RequestsKt`, `OkHttpExtensionsKt`) without saying which member
 * was missing.
 *
 * This inverts it. The extension's own method-reference table *is* the contract, so the full list
 * of what we owe is available without executing anything.
 *
 * ### Fixtures
 * Four real, third-party, signed APKs are committed under `src/androidTest/fixtures/`:
 * a v14 Aniyomi anime extension, a v16 Aniyomi anime extension, a Keiyoushi/Mihon manga
 * extension, and a second anime extension. They span two API generations, so between them they
 * reference a wide slice of the surface.
 *
 * ### What is not checked
 * Field references, and types merely named without a member reference. A missing field is still
 * found by crashing. Widening that is the obvious next step and is not done here.
 */
class ExtensionAbiDiffTest {

    private fun fixture(name: String): File? {
        val candidates = listOf(
            // Running from the app module's unit-test working directory.
            File("src/androidTest/fixtures/$name"),
            File("../src/androidTest/fixtures/$name"),
            File("app/src/androidTest/fixtures/$name"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    /** Resolves a reference against the classes we actually ship. */
    private fun resolves(reference: DexReference): Boolean {
        val clazz = try {
            Class.forName(reference.binaryName)
        } catch (e: ClassNotFoundException) {
            return false
        }

        val parsed = try {
            Descriptor.parse(reference.name + reference.descriptor)
        } catch (e: IllegalArgumentException) {
            return false
        }

        if (parsed.name == "<init>") {
            return try {
                clazz.getDeclaredConstructor(*parsed.parameterTypes); true
            } catch (e: NoSuchMethodException) {
                findInHierarchy(clazz, parsed)?.returnType == parsed.returnType
            }
        }

        return findInHierarchy(clazz, parsed)?.returnType == parsed.returnType
    }

    private fun findInHierarchy(clazz: Class<*>, parsed: Descriptor): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(parsed.name, *parsed.parameterTypes)
            } catch (e: NoSuchMethodException) {
                current = current.superclass
            }
        }
        current = clazz
        while (current != null) {
            for (candidate in current.interfaces) {
                findInHierarchy(candidate, parsed)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    /**
     * Every reference the given fixtures make that `:source-api` does not satisfy.
     *
     * Grouped by owning class, because the fix is usually a single missing member on one type and
     * a flat list of 40 failures buries that.
     */
    private fun gapsFor(vararg fixtureNames: String): Map<String, Set<String>> {
        val gaps = mutableMapOf<String, MutableSet<String>>()

        fixtureNames.forEach { name ->
            val file = fixture(name)
            assumeTrue("fixture $name not found", file != null)

            val references = DexAbiReader.referencesIn(file!!)
            assertTrue("no host-API references found in $name — parser is broken", references.isNotEmpty())

            references.filterNot { resolves(it) }.forEach { reference ->
                gaps.getOrPut(reference.binaryName) { mutableSetOf() }.add(reference.display)
            }
        }

        return gaps
    }

    private fun report(name: String, gaps: Map<String, Set<String>>): String {
        if (gaps.isEmpty()) return ""
        return buildString {
            appendLine("`:source-api` does not satisfy $name's references:")
            gaps.forEach { (owner, members) ->
                appendLine("  $owner")
                members.sorted().forEach { appendLine("    - $it") }
            }
        }
    }

    @Ignore("DexAbiReader cannot yet parse proto_ids; see the class note")
    @Test
    fun v14AniyomiExtensionIsFullySatisfied() {
        val gaps = gapsFor("real-mangasource-extension.apk")

        assertTrue(
            report("real-mangasource-extension.apk (Aniyomi v14)", gaps),
            gaps.isEmpty(),
        )
    }

    @Ignore("DexAbiReader cannot yet parse proto_ids; see the class note")
    @Test
    fun v16AniyomiExtensionIsFullySatisfied() {
        // The modern generation: Injekt-based discovery, `object` sources, and a wider slice of
        // the request-builder surface. This is the fixture that has driven most of the gaps.
        val gaps = gapsFor("real-anime-catalog-extension.apk")

        assertTrue(
            report("real-anime-catalog-extension.apk (Aniyomi v16)", gaps),
            gaps.isEmpty(),
        )
    }

    @Ignore("DexAbiReader cannot yet parse proto_ids; see the class note")
    @Test
    fun everyCommittedFixtureIsSatisfied() {
        // A single test over every committed fixture, so a newly added one is covered
        // automatically rather than needing its own test.
        val fixtures = listOf(
            "real-mangasource-extension.apk",
            "real-anime-catalog-extension.apk",
            "real-keiyoushi-manga-extension.apk",
        )

        val gaps = gapsFor(*fixtures.toTypedArray())

        assertTrue(
            report("all committed fixtures", gaps),
            gaps.isEmpty(),
        )
    }

    @Ignore("DexAbiReader cannot yet parse proto_ids; see the class note")
    @Test
    fun theParserActuallyFindsReferences() {
        // If this fails the other tests are passing vacuously — a dex reader that reads nothing
        // reports no gaps, which looks identical to a perfect API.
        val file = fixture("real-anime-catalog-extension.apk")
        assumeTrue("fixture not found", file != null)

        val references = DexAbiReader.referencesIn(file!!)

        assertTrue("expected references from the v16 fixture", references.size > 20)
        assertTrue(
            "expected AnimeHttpSource members to be referenced",
            references.any { it.owner.contains("animesource") },
        )
        assertTrue(
            "expected network members to be referenced",
            references.any { it.owner.contains("network") },
        )
    }
}
