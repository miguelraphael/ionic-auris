package com.auris.app.source

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal dex type-descriptor reader.
 *
 * Used only to enumerate class names in an extension APK. There is no public Android API for
 * this, and the alternatives are worse: `DexClassLoader` exposes no listing, and reflection
 * needs a name to look up in the first place.
 *
 * ### Scope and limits
 * This parses the dex *header and class_def table* only, which is enough to obtain type
 * descriptors. It deliberately does not decode method bodies, annotations or debug info — a
 * full dex parser would be a large amount of code to support a discovery step, and a
 * malformed body cannot hide a class name.
 *
 * ### Error policy
 * Any inconsistency returns an empty list. Discovery failing must not prevent the extension
 * from loading: the caller falls back to the classes it can reach reflectively.
 */
class DexFileReader(private val path: String) {

    /**
     * Binary class names of every class in an APK or jar, reading its dex entries.
     *
     * Reads through the archive rather than requiring the caller to extract first, so both
     * the loader and its tests work straight from the installed APK.
     */
    fun readClassNamesFromArchive(): List<String> {
        val names = mutableSetOf<String>()
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")

        runCatching {
            val zip = java.util.zip.ZipFile(path)
            zip.use {
                for (entry in zip.entries()) {
                    if (!entry.name.startsWith("classes") || !entry.name.endsWith(".dex")) continue

                    val out = File(tmpDir, "auris-${entry.name}-${System.nanoTime()}")
                    try {
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        out.setReadOnly()
                        names += readDescriptors(out.absolutePath)
                            .mapNotNull { it.toBinaryClassName() }
                    } finally {
                        out.setWritable(true)
                        out.delete()
                    }
                }
            }
        }.onFailure { return emptyList() }

        return names.toList()
    }

    /**
     * Binary class names (`com.example.Foo`) of every class defined in this dex.
     *
     * Returns names rather than descriptors on purpose: this feeds `ClassLoader.loadClass`,
     * which wants `com.example.Foo`. Handing it a `Lcom/example/Foo;` descriptor fails to
     * resolve and the class is silently skipped.
     */
    fun readClassNames(): List<String> = readClassDescriptors()
        .mapNotNull { it.toBinaryClassName() }

    /** Type descriptors (`Lcom/example/Foo;`) of every class defined in this dex. */
    fun readClassDescriptors(): List<String> = readClassDescriptorsOf(File(path))

    private fun readClassDescriptorsOf(file: File): List<String> = readDescriptors(file.absolutePath)

    private fun readDescriptors(path: String): List<String> {
        val file = File(path)
        // A missing or unreadable file is an empty result, never an exception: the loader
        // calls this while discovering sources and must not crash on one bad APK.
        if (!file.isFile || !file.canRead()) return emptyList()

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = readMagic(raf) ?: return@use emptyList()

                when {
                    magic.startsWith("dex\n") -> readDex(raf)
                    // Compact dex ("cdex"): a different container format. Returning empty
                    // degrades to "no sources discovered" rather than a wrong parse; ART
                    // builds normal dex for extensions, so this is a safety net only.
                    else -> emptyList<String>()
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun readMagic(raf: RandomAccessFile): String? {
        val bytes = ByteArray(8)
        raf.seek(0)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    /**
     * dex layout used here:
     *  - `header_size` (0x24), `string_ids_size` (0x38), `string_ids_off` (0x3C)
     *  - `type_ids_size` (0x40), `type_ids_off` (0x44)
     *  - `class_defs_size` (0x60), `class_defs_off` (0x64)
     *
     * A `class_def_item` is 32 bytes; its first `uint` is `class_idx`, an index into
     * `type_ids`, and each `type_id_item` is a `string_idx` into `string_ids`, whose data is a
     * MUTF-8 descriptor.
     */
    private fun readDex(raf: RandomAccessFile): List<String> {
        // A dex header is 0x70 bytes; anything else means the file is not a dex.
        if (readInt(raf, HEADER_SIZE_OFF) != DEX_HEADER_SIZE) return emptyList()

        val stringIdsSize = readInt(raf, 0x38)
        val stringIdsOff = readInt(raf, 0x3C)
        val typeIdsSize = readInt(raf, 0x40)
        val typeIdsOff = readInt(raf, 0x44)
        val classDefsSize = readInt(raf, 0x60)
        val classDefsOff = readInt(raf, 0x64)

        if (stringIdsSize <= 0 || typeIdsSize <= 0 || classDefsSize <= 0) return emptyList()

        val fileLength = raf.length()
        if (classDefsOff < 0 || classDefsOff + classDefsSize * CLASS_DEF_SIZE > fileLength) {
            return emptyList()
        }

        val descriptors = ArrayList<String>(classDefsSize)

        for (i in 0 until classDefsSize) {
            val classIdx = readInt(raf, classDefsOff + i * CLASS_DEF_SIZE)

            // Bounds check every hop: a corrupt dex must not throw, it must return fewer names.
            if (classIdx < 0 || classIdx >= typeIdsSize) continue

            val typeIdOff = typeIdsOff + classIdx * Int.SIZE_BYTES
            if (typeIdOff < 0 || typeIdOff + Int.SIZE_BYTES > fileLength) continue

            val stringIdx = readInt(raf, typeIdOff)
            if (stringIdx < 0 || stringIdx >= stringIdsSize) continue

            val stringOffPos = stringIdsOff + stringIdx * Int.SIZE_BYTES
            if (stringOffPos < 0 || stringOffPos + Int.SIZE_BYTES > fileLength) continue

            val stringDataOff = readInt(raf, stringOffPos)
            if (stringDataOff <= 0 || stringDataOff >= fileLength) continue

            readDescriptor(raf, stringDataOff)?.let(descriptors::add)
        }

        return descriptors
    }

    /**
     * Reads a `string_data_item`: ULEB128 `utf16_size`, then MUTF-8 bytes.
     *
     * The length is in UTF-16 units but is only used to bound the read, so reading to the
     * terminating NUL is both correct for ASCII descriptors and robust for the rest.
     */
    private fun readDescriptor(raf: RandomAccessFile, offset: Int): String? {
        raf.seek(offset.toLong())

        readUleb128(raf) // utf16_size
        val buffer = StringBuilder()
        while (true) {
            val b = raf.read()
            if (b <= 0) break
            buffer.append(b.toChar())
        }

        val descriptor = buffer.toString()
        return descriptor.takeIf {
            it.length in 3..MAX_DESCRIPTOR_LENGTH &&
                it.startsWith("L") &&
                it.endsWith(";")
        }
    }

    private fun readUleb128(raf: RandomAccessFile): Int {
        var result = 0
        var shift = 0
        while (shift < 32) {
            val b = raf.read()
            if (b < 0) return result
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return result
    }

    /**
     * Reads a little-endian `uint32`.
     *
     * dex is little-endian throughout, and [RandomAccessFile.readInt] is big-endian. Using
     * it directly yields byte-swapped values — `header_size` comes back as `0x70000000`
     * instead of `0x70` — which makes every offset wrong and the whole parse silently
     * return nothing.
     */
    private fun readInt(raf: RandomAccessFile, offset: Int): Int {
        raf.seek(offset.toLong())
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return -1
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private companion object {
        const val DEX_HEADER_SIZE = 0x70

        /** Offset of `header_size`, used as the "is this a dex?" sanity check. */
        const val HEADER_SIZE_OFF = 0x24
        const val CLASS_DEF_SIZE = 32

        /** Guards against reading an absurd descriptor from a corrupt offset. */
        const val MAX_DESCRIPTOR_LENGTH = 1024
    }
}

/**
 * Converts a type descriptor to the binary class name `ClassLoader.loadClass` expects.
 *
 * `Lcom/example/Foo;` -> `com.example.Foo`
 *
 * Dotted form, not the slash form still used internally by dex. `ClassLoader.loadClass`
 * documents the dotted name; the Android VM tolerates slashes, but relying on that would
 * break the moment this code runs on a stricter JVM (or a unit test).
 *
 * Only object types convert; primitives (`I`, `V`, ...) have no class name and yield null.
 */
internal fun String.toBinaryClassName(): String? {
    if (!startsWith("L") || !endsWith(";")) return null
    val internal = substring(1, length - 1)
    if (internal.isEmpty() || internal.startsWith("[")) return null
    return internal.replace('/', '.')
}