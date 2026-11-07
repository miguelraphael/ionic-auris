package com.auris.app.source.repo

import com.auris.app.data.local.entity.ExtensionEntity
import java.io.File

private val PACKAGE_NAME = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$""")

internal fun requireExtensionPackageName(packageName: String) {
    require(PACKAGE_NAME.matches(packageName)) { "Invalid extension package name" }
}

/** A package may name only a direct child, including when an existing file is a symlink. */
internal fun extensionFile(directory: File, packageName: String, suffix: String = ".apk"): File {
    requireExtensionPackageName(packageName)
    require(suffix == ".apk" || suffix == ".part")
    val file = File(directory, packageName + suffix)
    require(file.canonicalFile.parentFile == directory.canonicalFile) {
        "Extension file is outside the extensions directory"
    }
    return file
}

/** No first-use trust decision is allowed on reload: both installed pins must match. */
internal fun verifyInstalledExtension(
    installed: ExtensionEntity,
    identity: ExtensionEntity,
    observedSigners: Set<String>,
) {
    require(identity.packageName == installed.packageName) { "Installed extension identity changed" }
    val expectedHash = SignatureVerifier.normalize(installed.signatureHash)
    require(expectedHash != null && expectedHash == SignatureVerifier.normalize(identity.signatureHash)) {
        "Installed extension bytes changed; reinstall to verify"
    }
    require(SignatureVerifier.normalize(installed.signingCertFingerprint) != null) {
        "Installed extension has no publisher pin; reinstall to verify"
    }
    require(SignatureVerifier.verify(null, observedSigners, installed.signingCertFingerprint) is SignatureVerdict.Trusted) {
        "Installed extension publisher changed; reinstall to verify"
    }
}
