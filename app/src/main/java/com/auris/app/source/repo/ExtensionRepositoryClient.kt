package com.auris.app.source.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Fetches extension repository indexes and APKs.
 *
 * Repository responses are strictly bounded to prevent memory exhaustion or disk filling:
 * indexes are limited to [MAX_INDEX_BYTES], APKs are streamed directly to disk bounded
 * by [MIN_APK_BYTES] and [MAX_APK_BYTES], and icons are capped at [MAX_ICON_BYTES].
 *
 * APK certificate verification and pinning against repository fingerprints are handled
 * at the repository/installation boundary by [SignatureVerifier].
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
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code} for ${repo.indexUrl}")
                    }
                    val bodyStream = response.body?.byteStream()
                        ?: throw IOException("Empty index at ${repo.indexUrl}")

                    val buffer = ByteArray(8192)
                    val out = ByteArrayOutputStream()
                    var total = 0L
                    var bytesRead: Int
                    while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
                        total += bytesRead
                        if (total > MAX_INDEX_BYTES) {
                            throw IOException("Index response exceeded limit of $MAX_INDEX_BYTES bytes")
                        }
                        out.write(buffer, 0, bytesRead)
                    }

                    val body = out.toString(Charsets.UTF_8.name())
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
     * Streams directly to disk with strict byte bounds ([MIN_APK_BYTES] to [MAX_APK_BYTES])
     * to avoid allocating large byte arrays in heap memory or permitting disk-filling attacks.
     * Written read-only: dalvik refuses a writable dex/jar, and an installed extension must be
     * immutable anyway or a tampered file could be swapped in before the next load.
     */
    suspend fun downloadApk(
        extension: RemoteExtension,
        repo: ExtensionRepo,
        destination: File,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(extension.apkUrl(repo)).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} downloading ${extension.packageName}")
                }
                val bodyStream = response.body?.byteStream()
                    ?: throw IOException("Empty APK body for ${extension.packageName}")

                destination.parentFile?.mkdirs()
                var totalBytes = 0L
                try {
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
                            totalBytes += bytesRead
                            if (totalBytes > MAX_APK_BYTES) {
                                throw IOException(
                                    "APK size for ${extension.packageName} exceeded limit of $MAX_APK_BYTES bytes"
                                )
                            }
                            output.write(buffer, 0, bytesRead)
                        }
                    }

                    // An APK smaller than this cannot be a real extension; treating a truncated or
                    // HTML error page as an APK would fail later with a confusing loader error.
                    require(totalBytes >= MIN_APK_BYTES) {
                        "Downloaded ${extension.packageName} is only $totalBytes bytes"
                    }

                    destination.setReadOnly()
                    destination
                } catch (t: Throwable) {
                    destination.delete()
                    throw t
                }
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
                if (!response.isSuccessful) return@use null
                val bodyStream = response.body?.byteStream() ?: return@use null
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0L
                var bytesRead: Int
                while (bodyStream.read(buffer).also { bytesRead = it } != -1) {
                    total += bytesRead
                    if (total > MAX_ICON_BYTES) {
                        return@use null
                    }
                    out.write(buffer, 0, bytesRead)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    companion object {
        const val TAG = "AurisExtensions"
        const val MIN_APK_BYTES = 2 * 1024L
        const val MAX_APK_BYTES = 50 * 1024 * 1024L
        const val MAX_INDEX_BYTES = 5 * 1024 * 1024L
        const val MAX_ICON_BYTES = 2 * 1024 * 1024L
    }
}
