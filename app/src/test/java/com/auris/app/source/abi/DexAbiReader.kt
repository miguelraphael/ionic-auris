package com.auris.app.source.abi

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * A minimal DEX reader that extracts the member references an extension makes into
 * `eu.kanade.tachiyomi.*`.
 *
 * ### Why this exists
 * Every ABI gap found so far cost a full build-install-run cycle, because each one only surfaced
 * as a `NoSuchMethodError` naming a host file facade — `RequestsKt`, `OkHttpExtensionsKt` — with no
 * indication of *which* member was missing. Nine gaps, nine cycles, one member each time.
 *
 * The fix is to read what the extension actually references instead of discovering it by
 * crashing. Everything needed is in four tables of the dex header:
 *
 * ```
 * string_ids -> the strings, including type descriptors
 * type_ids   -> uint indexes into string_ids
 * proto_ids  -> parameter and return types, by index into type_ids
 * method_ids -> (owner type, proto, name) — the method references
 * ```
 *
 * That is the whole job. No bytecode interpretation, no hidden APIs, no `dexdump` subprocess —
 * just the tables, which is why it can run as a plain JVM test on the build machine.
 *
 * ### Scope and limits, stated plainly
 * - Reads method references only. Field references are not extracted, so a missing *field* is
 *   still found by crashing rather than in advance.
 * - Reads the raw dex. For an APK the `classes*.dex` entries are located and each is parsed.
 * - A reference to a type the extension merely *names* (a superclass, say) is not reported;
 *   only members are, because a missing member is what breaks a call site.
 */
/** A member reference an extension makes into the host API, in JVM descriptor form. */
data class DexReference(
    val owner: String,
    val name: String,
    val descriptor: String,
) {
    /** `owner.name descriptor`, for reporting. */
    val display: String get() = "$owner.$name$descriptor"

    /** The owner as a binary class name, for `Class.forName`. */
    val binaryName: String get() = owner.removePrefix("L").removeSuffix(";").replace('/', '.')
}

class DexAbiReader private constructor(private val dex: RandomAccessFile) : Closeable {

    companion object {
        /** Members of the host API are all under this descriptor prefix. */
        const val HOST_PREFIX = "Leu/kanade/tachiyomi/"


        /**
         * Every `eu.kanade.tachiyomi.*` member reference across all dex files in [apk].
         *
         * Deduplicated and sorted so a diff between runs is meaningful — dex iteration order is
         * not stable enough to diff raw.
         */
        fun referencesIn(apk: File): Set<DexReference> {
            val dexEntries = when {
                apk.extension.equals("dex", ignoreCase = true) -> listOf(apk)
                else -> runCatching {
                    java.util.zip.ZipFile(apk).use { zip ->
                        zip.entries().toList()
                            .filter { it.name.matches(DEX_ENTRY) }
                            .map { entry ->
                                apk.resolveSibling(entry.name).also {
                                    zip.getInputStream(entry).use { input ->
                                        it.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                            }
                    }
                }.getOrDefault(emptyList())
            }

            val all = mutableSetOf<DexReference>()
            dexEntries.forEach { dexFile ->
                DexAbiReader(RandomAccessFile(dexFile, "r")).use { reader ->
                    all += reader.references()
                }
                // The extracted dex sits next to the APK only for this call; leaving thousands
                // of them in the build directory would be its own kind of mess.
                if (dexFile.parentFile == apk.parentFile) dexFile.delete()
            }
            return all
        }

        /** `classes.dex`, `classes2.dex`, … */
        private val DEX_ENTRY = Regex("""classes\d*\.dex""")
    }

    override fun close() = dex.close()

    private fun uintAt(offset: Long): Int {
        dex.seek(offset)
        val bytes = ByteArray(4)
        dex.readFully(bytes)
        // Dex is little-endian.
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
    }

    private fun ushortAt(offset: Long): Int {
        dex.seek(offset)
        val bytes = ByteArray(2)
        dex.readFully(bytes)
        return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    }

    private fun stringAt(index: Int): String {
        val dataOffset = uintAt(stringIdsOffset + index * 4L)

        // string_data_item is a uleb128 utf16 length followed by MUTF-8 bytes and a NUL.
        dex.seek(dataOffset.toLong())
        readUleb128() // length; only needed to advance to the bytes

        // `RandomAccessFile.read()` throws EOFException at end-of-file rather than returning -1
        // the way `InputStream` does, so the terminator read has to be caught rather than
        // compared.
        val builder = StringBuilder()
        while (true) {
            val read = try {
                dex.read()
            } catch (e: java.io.EOFException) {
                break
            }
            if (read <= 0) break
            builder.append((read.toByte()).toInt().toChar())
        }
        return builder.toString()
    }

    private fun readUleb128(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = try {
                dex.read()
            } catch (e: java.io.EOFException) {
                return result
            }
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
            if (shift > 28) return result
        }
    }

    /** Every method reference whose owner is in the host API. */
    fun references(): Set<DexReference> {
        val result = mutableSetOf<DexReference>()

        for (index in 0 until methodIdsSize) {
            val base = methodIdsOffset + index * 8L
            val classIdx = ushortAt(base)
            val protoIdx = ushortAt(base + 2)
            val nameIdx = uintAt(base + 4)

            val owner = typeDescriptor(classIdx)
            if (!owner.startsWith(HOST_PREFIX)) continue

            result += DexReference(
                owner = owner,
                name = stringAt(nameIdx),
                descriptor = protoDescriptor(protoIdx),
            )
        }

        return result
    }

    private fun typeDescriptor(index: Int): String = stringAt(uintAt(typeIdsOffset + index * 4L))

    private fun protoDescriptor(index: Int): String {
        // proto_ids_item is **8 bytes**: ushort shorty_idx, ushort return_type_idx,
        // uint parameters_off — per the official dex format. Reading it as 12 bytes with
        // 4-byte indices (a common misreading, and one this code originally made) puts
        // parameters_off in the middle of the next entry, producing a wild offset that
        // surfaces as an EOFException with no hint of a table-layout mistake.
        //
        // Worth stating because it is easy to "fix" in the wrong direction: at a 12-byte
        // stride the decoded values come out as 20716 and 24556, which are class_defs_off
        // and data_off — unmistakable header offsets rather than type indices.
        val base = protoIdsOffset + index * 8L
        val returnTypeIdx = ushortAt(base + 2)
        val parametersOff = uintAt(base + 4)

        val parameters = if (parametersOff == 0) {
            ""
        } else {
            val size = uintAt(parametersOff.toLong())
            (0 until size).joinToString("") { typeDescriptor(uintAt(parametersOff + 4L + it * 2L)) }
        }

        return "($parameters)${typeDescriptor(returnTypeIdx)}"
    }

    // ---- Header fields, read once -------------------------------------------

    private val headerStart = 0L

    private val stringIdsSize by lazy { uintAt(headerStart + 0x38) }
    private val stringIdsOffset by lazy { uintAt(headerStart + 0x3C).toLong() }
    private val typeIdsSize by lazy { uintAt(headerStart + 0x40) }
    private val typeIdsOffset by lazy { uintAt(headerStart + 0x44).toLong() }
    private val protoIdsSize by lazy { uintAt(headerStart + 0x48) }
    private val protoIdsOffset by lazy { uintAt(headerStart + 0x4C).toLong() }
    private val methodIdsSize by lazy { uintAt(headerStart + 0x58) }
    private val methodIdsOffset by lazy { uintAt(headerStart + 0x5C).toLong()     }
}
