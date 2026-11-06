package com.auris.app.source

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexFileReaderUnitTest {

    @Test
    fun rejectsEntryExceedingMaxEntryDexBytes() {
        val zipFile = File.createTempFile("oversized-dex", ".zip")
        zipFile.deleteOnExit()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            // Write more than MAX_ENTRY_DEX_BYTES (35 MB) using a compressed stream.
            // A zip stream compresses identical bytes extremely well (~35 KB on disk for 36 MB raw).
            val chunk = ByteArray(1024 * 1024)
            repeat(36) {
                zos.write(chunk)
            }
            zos.closeEntry()
        }

        val reader = DexFileReader(zipFile.absolutePath)
        val names = reader.readClassNamesFromArchive()
        assertTrue("Zip bomb exceeding MAX_ENTRY_DEX_BYTES must return empty list", names.isEmpty())

        // Verify temp directory has no leftover auris-classes* files
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        val leftovers = tmpDir.listFiles { _, name -> name.startsWith("auris-classes") }
        assertTrue("Temporary files must be cleaned up on failure", leftovers.isNullOrEmpty())

        zipFile.delete()
        Unit
    }
}
