package com.auris.app.source.abi

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A JVM descriptor, parsed into something reflection can look up.
 *
 * ### Why not just use Kotlin reflection signatures
 * Because the thing under test is the **descriptor**. An assertion written as "there is a method
 * `getState` returning `Any?`" would still pass if the Kotlin type changed to `Boolean`, while
 * the emitted descriptor changed from `()Ljava/lang/Object;` to `()Z` — which is precisely one
 * of the five breaks that stopped real extensions loading. Writing the expectation as the exact
 * descriptor string makes that class of change impossible to miss.
 */
internal data class Descriptor(
    val name: String,
    val parameterTypes: Array<Class<*>>,
    val returnType: Class<*>,
    /** True when the expectation asked for a protected member. */
    val protected: Boolean,
    /** Retained for the modifier check in [SourceApiAbiTest.assertDescriptor]. */
    val parameterTypesModifier: Int = 0,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Descriptor) return false
        return name == other.name &&
            parameterTypes.contentEquals(other.parameterTypes) &&
            returnType == other.returnType &&
            protected == other.protected
    }

    override fun hashCode(): Int =
        (name.hashCode() * 31 + parameterTypes.contentHashCode()) * 31 + returnType.hashCode()

    companion object {

        /**
         * Parses `name(paramTypes):returnType`.
         *
         * Accepts an optional `protected ` prefix, which asserts accessibility as well as shape —
         * `ParsedAnimeHttpSource.network` is `protected` in Anikku and extensions rely on it not
         * being public surface.
         */
        fun parse(descriptor: String): Descriptor {
            var text = descriptor.trim()
            var requireProtected = false

            if (text.startsWith("protected ")) {
                requireProtected = true
                text = text.removePrefix("protected ").trim()
            }

            val open = text.indexOf('(')
            val close = text.indexOf(')', open)
            require(open > 0 && close > open) { "Malformed descriptor: $descriptor" }

            val name = text.substring(0, open)
            val returnType = text.substring(close + 1).removePrefix(":")
            if (returnType.isBlank()) {
                throw IllegalArgumentException("Descriptor `$descriptor` has no return type")
            }

            return Descriptor(
                name = name,
                parameterTypes = splitParameters(text.substring(open + 1, close))
                    .map { resolve(it) }
                    .toTypedArray(),
                returnType = resolve(returnType),
                protected = requireProtected,
                parameterTypesModifier = if (requireProtected) Modifier.PROTECTED else 0,
            )
        }

        /** The descriptor text of a real [Method], for failure messages. */
        fun of(method: Method): String {
            val visibility = when {
                Modifier.isProtected(method.modifiers) -> "protected "
                Modifier.isPublic(method.modifiers) -> ""
                else -> "private "
            }
            val parameters = method.parameterTypes.joinToString(",") { descriptorOf(it) }
            return "$visibility${method.name}($parameters):${descriptorOf(method.returnType)}"
        }

        /**
         * Tokenises a JVM parameter list into one token per parameter.
         *
         * ### Why semicolons, and why no comma handling at all
         * The parameter list in a raw descriptor is delimited by `;`, not `,`:
         *
         * ```
         * (Ljava/lang/String;Ljava/util/List;)V   two parameters
         * ```
         *
         * An earlier version split on commas, which merged `String` and `List` into one
         * unresolvable token that then reported as "the method does not exist" — pointing at the
         * wrong problem entirely. Every expectation in this suite is therefore written in
         * canonical form, and a comma is rejected rather than guessed at: silently accepting a
         * non-canonical form would reintroduce exactly the ambiguity just removed.
         */
        internal fun splitParameters(parameters: String): List<String> {
            val canonical = parameters

            if (canonical.isEmpty()) return emptyList()

            val tokens = mutableListOf<String>()
            var index = 0

            while (index < canonical.length) {
                val start = index

                // Array prefixes bind to the type that follows them.
                while (index < canonical.length && canonical[index] == '[') index++

                when {
                    index >= canonical.length ->
                        fail("Dangling array prefix in descriptor parameters `$canonical`")

                    canonical[index] == 'L' -> {
                        val end = canonical.indexOf(';', index)
                        if (end < 0) {
                            fail("Unterminated object type in descriptor parameters `$canonical`")
                        }
                        // Reject anything that cannot appear in a JVM internal name. Without this
                        // a stray comma is absorbed into the name and the parameter list silently
                        // tokenises as a single type — which then fails to resolve and reports as
                        // "the method does not exist", pointing at the wrong problem.
                        val name = canonical.substring(index + 1, end)
                        val illegal = name.firstOrNull { it in ILLEGAL_IN_TYPE_NAME }
                        if (illegal != null) {
                            fail(
                                "Character `$illegal` in `$name` is not valid in a JVM type name. " +
                                    "Parameters are separated by `;`, not `,`.",
                            )
                        }
                        index = end + 1
                    }

                    // A primitive occupies exactly one character and has no terminator.
                    canonical[index] in PRIMITIVES -> index++

                    else -> fail(
                        "Unexpected character `${canonical[index]}` in descriptor parameters " +
                            "`$canonical`",
                    )
                }

                tokens += canonical.substring(start, index)
            }

            return tokens
        }

        private const val PRIMITIVES = "VZBCSIJFD"

        /** Characters that signal a mis-written descriptor rather than a type name. */
        private const val ILLEGAL_IN_TYPE_NAME = ",()<>:. \t"

        private fun descriptorOf(type: Class<*>): String = when {
            type.isArray -> "[" + descriptorOf(type.componentType)
            type == Void.TYPE -> "V"
            type == Boolean::class.javaPrimitiveType -> "Z"
            type == Byte::class.javaPrimitiveType -> "B"
            type == Char::class.javaPrimitiveType -> "C"
            type == Short::class.javaPrimitiveType -> "S"
            type == Int::class.javaPrimitiveType -> "I"
            type == Long::class.javaPrimitiveType -> "J"
            type == Float::class.javaPrimitiveType -> "F"
            type == Double::class.javaPrimitiveType -> "D"
            else -> "L" + type.name.replace('.', '/') + ";"
        }

        /**
         * Resolves a JVM type name to a [Class].
         *
         * Arrays and primitives are handled directly; everything else goes through
         * [Class.forName]. A name that cannot be resolved fails the test with the name attached,
         * since an unresolvable type is itself the ABI break being looked for.
         */
        private fun resolve(name: String): Class<*> {
            if (name.startsWith("[")) {
                val component = resolve(name.removePrefix("["))
                return java.lang.reflect.Array.newInstance(component, 0).javaClass
            }

            if (name.endsWith(";")) {
                // Strip **both** sigils: the leading `L` and the trailing `;`. Removing only the
                // `;` leaves `Ljava.lang.String`, which does not resolve — and the resulting
                // "cannot resolve java.lang.String" is a genuinely misleading error.
                val fqcn = name.removePrefix("L").removeSuffix(";").replace('/', '.')
                val resolved: Class<*>? = try {
                    Class.forName(fqcn)
                } catch (e: ClassNotFoundException) {
                    null
                }
                return checkNotNull(resolved) {
                    "Cannot resolve `$fqcn` while checking the `:source-api` ABI. Either the " +
                        "type was renamed or the dependency providing it was removed — both " +
                        "break extension linking."
                }
            }

            val primitive: Class<*>? = when (name) {
                "V" -> Void.TYPE
                "Z" -> Boolean::class.javaPrimitiveType
                "B" -> Byte::class.javaPrimitiveType
                "C" -> Char::class.javaPrimitiveType
                "S" -> Short::class.javaPrimitiveType
                "I" -> Int::class.javaPrimitiveType
                "J" -> Long::class.javaPrimitiveType
                "F" -> Float::class.javaPrimitiveType
                "D" -> Double::class.javaPrimitiveType
                else -> null
            }
            if (primitive == null) fail("Unknown type token `$name` in a descriptor")
            return primitive
        }

        private fun fail(message: String): Nothing = throw AssertionError(message)
    }
}
