package com.auris.app.source.repo

import com.auris.app.data.local.entity.ExtensionEntity
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionFilesTest {
    private val hash = "a".repeat(64)
    private val signer = "b".repeat(64)
    private val installed = ExtensionEntity(
        packageName = "com.example.extension", label = "Test", versionName = "1", versionCode = 1,
        apkPath = "/extensions/com.example.extension.apk", signatureHash = hash,
        signingCertFingerprint = signer,
    )

    @Test fun `invalid repository package names fail during index decoding`() {
        for (packageName in listOf("../databases/x", "/absolute", "com..example", "com/example", "com.example\\escape")) {
            val encoded = Json.encodeToString(RemoteExtension.serializer(), remote("com.example.valid"))
                .replace("com.example.valid", packageName.replace("\\", "\\\\"))
            assertTrue(packageName, runCatching { Json.decodeFromString(RemoteExtension.serializer(), encoded) }.isFailure)
        }
    }

    @Test fun `package files stay in the extension directory`() {
        val directory = Files.createTempDirectory("extensions").toFile()
        assertEquals(File(directory, installed.packageName + ".apk"), extensionFile(directory, installed.packageName))
        assertTrue(runCatching { extensionFile(directory, "../outside") }.isFailure)
    }

    @Test fun `existing symlink cannot redirect install or uninstall outside directory`() {
        val directory = Files.createTempDirectory("extensions").toFile()
        val outside = File.createTempFile("outside", ".apk")
        Files.createSymbolicLink(File(directory, installed.packageName + ".apk").toPath(), outside.toPath())
        assertTrue(runCatching { extensionFile(directory, installed.packageName) }.isFailure)
        assertTrue(outside.exists())
    }

    @Test fun `installed bytes and publisher pins must both match`() {
        verifyInstalledExtension(installed, installed, setOf(signer))
        assertTrue(runCatching { verifyInstalledExtension(installed, installed.copy(signatureHash = "c".repeat(64)), setOf(signer)) }.isFailure)
        assertTrue(runCatching { verifyInstalledExtension(installed, installed, setOf("c".repeat(64))) }.isFailure)
        assertTrue(runCatching { verifyInstalledExtension(installed, installed.copy(packageName = "com.other.extension"), setOf(signer)) }.isFailure)
    }

    @Test fun `legacy unpinned and missing hash extensions fail closed`() {
        assertTrue(runCatching { verifyInstalledExtension(installed.copy(signingCertFingerprint = null), installed, setOf(signer)) }.isFailure)
        assertTrue(runCatching { verifyInstalledExtension(installed.copy(signatureHash = ""), installed, setOf(signer)) }.isFailure)
        assertTrue(runCatching { verifyInstalledExtension(installed, installed, emptySet()) }.isFailure)
    }

    private fun remote(packageName: String) = RemoteExtension(
        name = "Test", packageName = packageName, apkFileName = "test.apk", lang = "en",
        versionCode = 1, versionName = "1",
    )
}
