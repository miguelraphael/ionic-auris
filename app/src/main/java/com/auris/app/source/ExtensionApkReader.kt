package com.auris.app.source

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.net.toUri
import com.auris.app.data.local.entity.ExtensionEntity
import java.io.File
import java.io.IOException

/**
 * Reads identity from an extension APK's manifest.
 *
 * Extensions do not self-report: `packageName`, `versionCode` and `versionName` are read
 * from the APK with [PackageManager], because those are the values the update checker
 * compares and the values the host shows in the extension list.
 *
 * ### Note on source identity
 * The package name identifies the *extension*; a single extension can host many sources,
 * each with its own generated id (see `AnimeHttpSource.generateId`). Only the extension's
 * package name belongs here.
 */
object ExtensionApkReader {

    /**
     * Parses the manifest of the APK at [apkFile].
     *
     * @throws IOException if the APK cannot be read at all (missing, truncated, not an APK)
     */
    fun read(context: Context, apkFile: File): ExtensionEntity {
        require(apkFile.isFile) { "Extension APK not found at ${apkFile.absolutePath}" }

        val packageManager = context.packageManager
        val info = try {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            throw IOException("Not a readable APK: ${apkFile.absolutePath}", e)
        } ?: throw IOException("Could not parse APK manifest: ${apkFile.absolutePath}")

        val packageName = info.packageName
            ?: throw IOException("APK manifest has no package name: ${apkFile.absolutePath}")

        // The extension icon is not in the APK manifest as a resource id we can trust across
        // hosts, so the canonical index entry is the source of the icon later.
        val label = runCatching {
            info.applicationInfo?.let { packageManager.getApplicationLabel(it).toString() }
        }.getOrNull() ?: packageName

        return ExtensionEntity(
            packageName = packageName,
            versionName = info.versionName ?: "0",
            versionCode = info.versionCode.toLong(),
            apkPath = apkFile.absolutePath,
            label = label,
            signatureHash = apkFile.sha256(),
        )
    }

    /**
     * SHA-256 of the APK, used to detect that a reinstall changed the binary even when
     * `versionCode` did not move.
     */
    private fun File.sha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The legacy extension id scheme: the host package prefixed onto the extension package.
     * Aniyomi uses this so an installed extension is only visible to its host.
     */
    fun legacyExtensionId(hostPackage: String, extensionPackage: String): String =
        "extension.$extensionPackage"

    /** Content URI for an installed extension, used when re-exporting after reinstall. */
    fun apkUri(file: File): Uri = Uri.fromFile(file)
}