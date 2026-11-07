package com.auris.app.data.repository

import com.auris.app.core.model.MediaType
import com.auris.app.data.local.ExtensionDao
import com.auris.app.data.local.entity.ExtensionEntity
import java.io.File
import com.auris.app.source.repo.extensionFile

/**
 * Owns the installed-extension list: install, uninstall, enable/disable and update checks.
 *
 * Only bookkeeping lives here. Nothing in this class loads an APK — that is
 * [com.auris.app.source.ExtensionLoader]'s job, and keeping the split means the extension
 * list renders without paying for dex loading, and the UI never touches a file it does not own.
 */
class ExtensionRepository(
    private val extensionDao: ExtensionDao,
) {

    /** Every installed extension, for the extension manager screen. */
    fun installed() = extensionDao.observeInstalled()

    /** Only enabled extensions — the set that gets loaded. */
    fun enabled() = extensionDao.observeEnabled()

    fun observeEnabledCount() = extensionDao.observeEnabledCount()

    suspend fun find(packageName: String): ExtensionEntity? = extensionDao.find(packageName)

    /**
     * Records an extension, replacing metadata while preserving the user's enabled choice.
     *
     * @return the stored entity
     */
    suspend fun register(extension: ExtensionEntity): ExtensionEntity {
        val registered = extension.copy(enabled = extensionDao.find(extension.packageName)?.enabled ?: extension.enabled)
        extensionDao.upsert(registered)
        return registered
    }

    suspend fun registerAll(extensions: List<ExtensionEntity>) = extensionDao.upsertAll(extensions)

    suspend fun setEnabled(packageName: String, enabled: Boolean) =
        extensionDao.setEnabled(packageName, enabled)

    /**
     * Removes an extension and deletes its APK.
     *
     * The file is deleted here rather than left to the caller because a stale APK is worse
     * than none: on the next install it could be loaded again in place of the new one.
     */
    suspend fun uninstall(packageName: String, extensionsDir: File) {
        val apk = extensionFile(extensionsDir, packageName)
        if (apk.exists()) check(apk.delete()) { "Could not remove extension APK" }
        extensionDao.delete(packageName)
    }

    /**
     * Whether [candidate] should replace the installed extension.
     *
     * True when the package is not installed, when `versionCode` is higher, or when the APK
     * bytes changed at the same `versionCode` — some extension repos do republish a build
     * without moving the version, and a stale dex would otherwise persist.
     */
    suspend fun isUpdate(candidate: ExtensionEntity): Boolean {
        val installed = extensionDao.find(candidate.packageName) ?: return true
        if (candidate.versionCode > installed.versionCode) return true
        if (candidate.versionCode < installed.versionCode) return false
        return candidate.signatureHash != installed.signatureHash
    }

    companion object {
        /**
         * The `library_entries.source_id` for a source hosted by [packageName].
         *
         * Derived only from the package name, never from the APK version: `versionId` inside
         * the source handles "site changed incompatibly", while the package name identifies
         * the extension. Bumping the extension version must not orphan a user's library.
         */
        fun sourceIdFor(packageName: String): String = packageName

        /** The media type an extension's sources provide, from its package name. */
        fun mediaTypeFor(packageName: String): MediaType = when {
            packageName.contains("animeextension") -> MediaType.ANIME
            packageName.contains("mangaextension") -> MediaType.MANGA
            else -> MediaType.ANIME
        }
    }
}