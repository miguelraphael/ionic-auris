package com.auris.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An installed extension APK.
 *
 * Only the APK's *identity* lives here. Nothing about the sources inside it is cached: source
 * metadata comes from the loaded classes, because the classes are the source of truth and a
 * stale cache would misreport what a source can actually do.
 */
@Entity(
    tableName = "installed_extensions",
    indices = [
        // Extension updates are keyed by package; the versionCode decides whether to load.
        Index(value = ["package_name"], unique = true),
        Index("enabled"),
    ],
)
data class ExtensionEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "version_name")
    val versionName: String,

    @ColumnInfo(name = "version_code")
    val versionCode: Long,

    /** Absolute path to the APK inside the app's private extensions directory. */
    @ColumnInfo(name = "apk_path")
    val apkPath: String,

    /**
     * SHA-256 of the APK. Catches a reinstall that reused a `versionCode` but changed the
     * binary, which would otherwise leave a stale dex loaded.
     */
    @ColumnInfo(name = "signature_hash")
    val signatureHash: String,

    /**
     * SHA-256 of the APK's **signing certificate**, lower-case hex — not the file hash above.
     *
     * This is the publisher's identity. Set on install from [ApkSigner], and thereafter used as a
     * pin: an update signed by a different key is refused even if the repository has started
     * claiming that key. Null means the extension predates verification and has not been
     * re-checked yet.
     */
    @ColumnInfo(name = "signing_cert_fingerprint")
    val signingCertFingerprint: String? = null,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** Remote index URL this extension updates from; null for manually installed APKs. */
    @ColumnInfo(name = "repo_url")
    val repoUrl: String? = null,

    @ColumnInfo(name = "installed_at")
    val installedAt: Long = System.currentTimeMillis(),
)