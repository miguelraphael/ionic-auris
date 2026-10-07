package com.auris.app.source.repo

import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import java.security.MessageDigest

/**
 * Reads the signing certificate fingerprints out of an extension APK.
 *
 * ### Why this is not the same as [ExtensionEntity.signatureHash]
 * `signature_hash` is a SHA-256 of the **whole APK file**. It answers "are these the same bytes
 * I downloaded last time?" — it says nothing about *who* signed them. A file hash is computed by
 * whoever supplies the file, so an attacker who replaces the APK computes a perfectly matching
 * new hash.
 *
 * The signing **certificate** fingerprint is the part that identifies the publisher. It cannot be
 * chosen freely, because producing a certificate with a given public key requires the private key
 * that the real publisher holds. This is the only value that makes "is this really the extension
 * I asked for?" a meaningful question.
 *
 * ### Why PackageManager and not META-INF parsing
 * A common approach is reading the signature files under `META-INF` with a `CertificateFactory`. That only works for
 * v1 (JAR) signatures. Modern APKs are commonly signed with **v2/v3 APK Signature Scheme only**,
 * which stores the certificate in a signing block *before* the central directory and leaves no
 * `.RSA` file at all — so the classic approach silently reports "unsigned" on a correctly signed
 * APK.
 *
 * [PackageManager.getPackageArchiveInfo] reads the real signing block and reports whichever
 * scheme the APK actually uses.
 */
object ApkSigner {

    /**
     * Every signing certificate fingerprint in [apkPath], lower-case hex, no separators.
     *
     * Empty when the file is not a readable APK or is unsigned. Order is not significant.
     *
     * Returns a **set** because of signing-key rotation: Android lets a publisher ship a new key
     * while the old one still validates, so an APK can legitimately carry two certificates. A
     * verifier that only looked at the first would reject a rotated key as a forgery.
     */
    fun fingerprints(pm: PackageManager, apkPath: String): Set<String> {
        val info = runCatching {
            pm.getPackageArchiveInfo(apkPath, signingFlags())
        }.getOrNull() ?: return emptySet()

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

        return signatures.orEmpty()
            .mapNotNull { signature ->
                runCatching {
                    MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                }.getOrNull()?.toHex()
            }
            .toSet()
    }

    /**
     * Flags to request the *signing certificates* rather than the deprecated signature array.
     *
     * `GET_SIGNING_CERTIFICATES` is only honoured from API 28. On 26-27 the constant exists but
     * is not implemented, so asking for it returns an info object with no signers at all — a
     * silent "no signatures", which reads as *unsigned* and would make every extension on those
     * versions look tampered with.
     */
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
}

/** Lower-case hex, no separators — the canonical form used for every comparison. */
private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
