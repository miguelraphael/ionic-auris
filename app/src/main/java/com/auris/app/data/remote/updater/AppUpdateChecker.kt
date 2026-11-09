package com.auris.app.data.remote.updater

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface AppUpdateResult {
    data class Available(
        val version: String,
        val htmlUrl: String,
        val bodySnippet: String?,
    ) : AppUpdateResult

    data class UpToDate(val version: String) : AppUpdateResult
    data object Throttled : AppUpdateResult
    data class Error(val message: String) : AppUpdateResult
}

data class GitHubRelease(
    val tagName: String,
    val htmlUrl: String,
    val body: String?,
)

data class AppVersion(
    val parts: List<Int>,
    val suffix: String? = null,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val maxLen = maxOf(parts.size, other.parts.size)
        for (i in 0 until maxLen) {
            val p1 = parts.getOrElse(i) { 0 }
            val p2 = other.parts.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        if (suffix == null && other.suffix != null) return 1
        if (suffix != null && other.suffix == null) return -1
        if (suffix != null && other.suffix != null) return suffix.compareTo(other.suffix)
        return 0
    }

    companion object {
        fun parse(raw: String): AppVersion {
            val clean = raw.trim().removePrefix("v").removePrefix("V")
            val base = clean.substringBefore('-').substringBefore('+')
            val suffix = if (clean.contains('-')) clean.substringAfter('-') else null
            val parts = base.split('.').mapNotNull { it.toIntOrNull() }
            return AppVersion(parts.ifEmpty { listOf(0) }, suffix)
        }
    }
}

class AppUpdateChecker(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val sharedPreferences: SharedPreferences,
    private val apiUrl: String = DEFAULT_API_URL,
    private val currentVersion: String = com.auris.app.BuildConfig.VERSION_NAME,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        httpClient: OkHttpClient = OkHttpClient(),
        apiUrl: String = DEFAULT_API_URL,
        currentVersion: String = com.auris.app.BuildConfig.VERSION_NAME,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(
        httpClient = httpClient,
        sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        apiUrl = apiUrl,
        currentVersion = currentVersion,
        clock = clock,
    )

    suspend fun checkForUpdates(force: Boolean = false): AppUpdateResult = withContext(Dispatchers.IO) {
        val now = clock()
        val lastCheck = sharedPreferences.getLong(KEY_LAST_CHECK_MS, 0L)
        val isThrottled = !force && lastCheck > 0L && (now - lastCheck in 0 until THROTTLE_DURATION_MS)

        if (isThrottled) {
            val cachedVersion = sharedPreferences.getString(KEY_CACHED_VERSION, null)
            val cachedUrl = sharedPreferences.getString(KEY_CACHED_URL, null)
            val cachedBody = sharedPreferences.getString(KEY_CACHED_BODY, null)
            if (!cachedVersion.isNullOrBlank() && isNewerVersion(cachedVersion, currentVersion)) {
                return@withContext AppUpdateResult.Available(
                    version = cachedVersion,
                    htmlUrl = cachedUrl.orEmpty(),
                    bodySnippet = formatReleaseSnippet(cachedBody),
                )
            }
            return@withContext AppUpdateResult.Throttled
        }

        try {
            val release = fetchLatestRelease()
            sharedPreferences.edit()
                .putLong(KEY_LAST_CHECK_MS, now)
                .apply()

            if (release == null) {
                return@withContext AppUpdateResult.UpToDate(currentVersion)
            }

            val tagName = release.tagName
            if (isNewerVersion(tagName, currentVersion)) {
                sharedPreferences.edit()
                    .putString(KEY_CACHED_VERSION, tagName)
                    .putString(KEY_CACHED_URL, release.htmlUrl)
                    .putString(KEY_CACHED_BODY, release.body)
                    .apply()

                AppUpdateResult.Available(
                    version = tagName,
                    htmlUrl = release.htmlUrl,
                    bodySnippet = formatReleaseSnippet(release.body),
                )
            } else {
                sharedPreferences.edit()
                    .remove(KEY_CACHED_VERSION)
                    .remove(KEY_CACHED_URL)
                    .remove(KEY_CACHED_BODY)
                    .apply()

                AppUpdateResult.UpToDate(currentVersion)
            }
        } catch (e: Exception) {
            AppUpdateResult.Error(e.message ?: "Failed to check for updates")
        }
    }

    private fun fetchLatestRelease(): GitHubRelease? {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Auris-App")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: return null
                return parseReleaseJson(bodyStr)
            } else if (response.code == 404 && apiUrl.endsWith("/latest")) {
                val fallbackUrl = apiUrl.removeSuffix("/latest")
                val fallbackReq = Request.Builder()
                    .url(fallbackUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Auris-App")
                    .build()
                httpClient.newCall(fallbackReq).execute().use { fallbackResp ->
                    if (fallbackResp.isSuccessful) {
                        val fallbackBody = fallbackResp.body?.string() ?: return null
                        val array = Json.parseToJsonElement(fallbackBody).jsonArray
                        if (array.isNotEmpty()) {
                            val firstObj = array[0].jsonObject
                            return GitHubRelease(
                                tagName = firstObj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                htmlUrl = firstObj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                body = firstObj["body"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    }
                }
            } else {
                throw IOException("GitHub update check failed with HTTP ${response.code}")
            }
        }
        return null
    }

    private fun parseReleaseJson(jsonString: String): GitHubRelease {
        val obj = Json.parseToJsonElement(jsonString).jsonObject
        return GitHubRelease(
            tagName = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            body = obj["body"]?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/miguelraphael/ionic-auris/releases/latest"
        const val PREFS_NAME = "auris_updater"
        const val KEY_LAST_CHECK_MS = "last_update_check_ms"
        const val KEY_CACHED_VERSION = "cached_version"
        const val KEY_CACHED_URL = "cached_url"
        const val KEY_CACHED_BODY = "cached_body"
        const val THROTTLE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

        fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            if (remoteTag.isBlank() || currentVersion.isBlank()) return false
            val remote = AppVersion.parse(remoteTag)
            val current = AppVersion.parse(currentVersion)
            return remote > current
        }

        fun formatReleaseSnippet(body: String?, maxLength: Int = 200): String? {
            if (body.isNullOrBlank()) return null
            val clean = body.lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
            return if (clean.length > maxLength) {
                clean.take(maxLength).trimEnd() + "…"
            } else {
                clean
            }
        }
    }
}
