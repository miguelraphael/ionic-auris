package com.auris.app.source.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Fetches extension repository indexes and APKs.
 *
 * ### Signature verification — not implemented
 * Anikku authenticates `index.min.json` with the repository's Ed25519 signing key and refuses
 * to install an APK whose signature does not match the index. This client does **not** verify
 * anything yet: it parses and downloads, and treats the repo URL as user-supplied and trusted.
 *
 * That is a deliberate, visible gap rather than an oversight — see
 * [ExtensionRepo.signingKeyFingerprint]. Until verification lands, the UI states that
 * extensions are from an unverified source, and only HTTPS repos should be added.
 */
class ExtensionRepositoryClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    /** Reads and parses a repository index. Empty on failure, with the reason logged. */
    suspend fun fetchIndex(repo: ExtensionRepo): Result<List<RemoteExtension>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(repo.indexUrl).build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for ${repo.indexUrl}")
                    }
                    if (body.isBlank()) {
                        throw IOException("Empty index at ${repo.indexUrl}")
                    }

                    json.decodeFromString<List<RemoteExtension>>(body)
                }
            }.onFailure { error ->
                Log.w(TAG, "Failed to read index for ${repo.name}: ${error.message}")
            }
        }

    /**
     * Downloads an APK into [destination].
     *
     * Written read-only: dalvik refuses a writable dex/jar, and an installed extension must be
     * immutable anyway or a tampered file could be swapped in before the next load.
     */
    suspend fun downloadApk(
        extension: RemoteExtension,
        repo: ExtensionRepo,
        destination: java.io.File,
    ): Result<java.io.File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(extension.apkUrl(repo)).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} downloading ${extension.packageName}")
                }
                val bytes = response.body?.bytes()
                    ?: throw IOException("Empty APK body for ${extension.packageName}")

                // An APK smaller than this cannot be a real extension; treating a truncated or
                // HTML error page as an APK would fail later with a confusing loader error.
                require(bytes.size > MIN_APK_BYTES) {
                    "Downloaded ${extension.packageName} is only ${bytes.size} bytes"
                }

                destination.parentFile?.mkdirs()
                destination.outputStream().use { it.write(bytes) }
                destination.setReadOnly()
                destination
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to download ${extension.packageName}: ${error.message}")
            runCatching { destination.delete() }
        }
    }

    /** Fetches a repository icon. Returns null rather than throwing; icons are cosmetic. */
    suspend fun fetchIcon(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "AurisExtensions"
        const val MIN_APK_BYTES = 2 * 1024
    }
}