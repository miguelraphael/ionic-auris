package com.auris.app.data.remote.updater

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AppUpdateCheckerTest {

    private lateinit var server: MockWebServer
    private lateinit var fakePrefs: FakeSharedPreferences
    private var currentTimeMs: Long = 100_000_000L

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        fakePrefs = FakeSharedPreferences()
        currentTimeMs = 100_000_000L
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun versionComparisonRecognizesNewerVersions() {
        // Semver with and without 'v' prefix
        assertTrue(AppUpdateChecker.isNewerVersion("v0.1.13", "0.1.12"))
        assertTrue(AppUpdateChecker.isNewerVersion("0.1.13", "0.1.12"))
        assertTrue(AppUpdateChecker.isNewerVersion("V0.1.13", "v0.1.12"))
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0.0", "0.9.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("0.2.0", "0.1.12"))

        // Multi-digit component comparisons
        assertTrue(AppUpdateChecker.isNewerVersion("0.1.10", "0.1.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("0.10.0", "0.9.5"))
        assertTrue(AppUpdateChecker.isNewerVersion("10.0.0", "9.9.9"))

        // Different number of components
        assertTrue(AppUpdateChecker.isNewerVersion("1.0.1", "1.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("1.2.1", "1.2"))

        // Suffix handling
        assertTrue(AppUpdateChecker.isNewerVersion("v0.1.13", "v0.1.13-beta"))
    }

    @Test
    fun versionComparisonRejectsEqualOrOlderVersions() {
        // Equal versions
        assertFalse(AppUpdateChecker.isNewerVersion("v0.1.12", "0.1.12"))
        assertFalse(AppUpdateChecker.isNewerVersion("0.1.12", "v0.1.12"))
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.0", "1.0.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("1.2.0", "1.2"))

        // Older versions
        assertFalse(AppUpdateChecker.isNewerVersion("v0.1.11", "0.1.12"))
        assertFalse(AppUpdateChecker.isNewerVersion("0.1.9", "0.1.10"))
        assertFalse(AppUpdateChecker.isNewerVersion("0.0.9", "0.1.0"))

        // Pre-release compared to final of same version
        assertFalse(AppUpdateChecker.isNewerVersion("v0.1.12-beta", "0.1.12"))

        // Empty / invalid
        assertFalse(AppUpdateChecker.isNewerVersion("", "0.1.12"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.1.12", ""))
    }

    @Test
    fun checkForUpdatesIdentifiesNewerRelease() = runBlocking {
        val releaseJson = """
            {
                "tag_name": "v0.1.13",
                "html_url": "https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.13",
                "body": "Bug fixes and improvements"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))

        val checker = AppUpdateChecker(
            httpClient = OkHttpClient(),
            sharedPreferences = fakePrefs,
            apiUrl = server.url("/releases/latest").toString(),
            currentVersion = "0.1.12",
            clock = { currentTimeMs },
        )

        val result = checker.checkForUpdates(force = false)
        assertTrue(result is AppUpdateResult.Available)
        val available = result as AppUpdateResult.Available
        assertEquals("v0.1.13", available.version)
        assertEquals("https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.13", available.htmlUrl)
        assertEquals("Bug fixes and improvements", available.bodySnippet)

        // Verifies timestamp was persisted
        assertEquals(currentTimeMs, fakePrefs.getLong(AppUpdateChecker.KEY_LAST_CHECK_MS, 0L))
        assertEquals("v0.1.13", fakePrefs.getString(AppUpdateChecker.KEY_CACHED_VERSION, null))
    }

    @Test
    fun checkForUpdatesReportsUpToDateWhenSameVersion() = runBlocking {
        val releaseJson = """
            {
                "tag_name": "v0.1.12",
                "html_url": "https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.12",
                "body": "Current version notes"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))

        val checker = AppUpdateChecker(
            httpClient = OkHttpClient(),
            sharedPreferences = fakePrefs,
            apiUrl = server.url("/releases/latest").toString(),
            currentVersion = "0.1.12",
            clock = { currentTimeMs },
        )

        val result = checker.checkForUpdates(force = false)
        assertTrue(result is AppUpdateResult.UpToDate)
        assertEquals("0.1.12", (result as AppUpdateResult.UpToDate).version)
        assertEquals(currentTimeMs, fakePrefs.getLong(AppUpdateChecker.KEY_LAST_CHECK_MS, 0L))
    }

    @Test
    fun automaticCheckIsThrottledWithin24Hours() = runBlocking {
        val releaseJson = """
            {
                "tag_name": "v0.1.13",
                "html_url": "https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.13",
                "body": "First check notes"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))

        val checker = AppUpdateChecker(
            httpClient = OkHttpClient(),
            sharedPreferences = fakePrefs,
            apiUrl = server.url("/releases/latest").toString(),
            currentVersion = "0.1.12",
            clock = { currentTimeMs },
        )

        // 1. First automatic check succeeds and hits server
        val firstResult = checker.checkForUpdates(force = false)
        assertTrue(firstResult is AppUpdateResult.Available)
        assertEquals(1, server.requestCount)

        // 2. Second automatic check 2 hours later (within 24h) is throttled and returns cached Available without hitting network
        currentTimeMs += 2 * 60 * 60 * 1000L
        val secondResult = checker.checkForUpdates(force = false)
        assertTrue(secondResult is AppUpdateResult.Available)
        assertEquals("v0.1.13", (secondResult as AppUpdateResult.Available).version)
        assertEquals(1, server.requestCount) // Server NOT queried again!

        // 3. Manual check with force = true bypasses throttle
        val newerReleaseJson = """
            {
                "tag_name": "v0.1.14",
                "html_url": "https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.14",
                "body": "Forced check notes"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(newerReleaseJson))

        val forcedResult = checker.checkForUpdates(force = true)
        assertTrue(forcedResult is AppUpdateResult.Available)
        assertEquals("v0.1.14", (forcedResult as AppUpdateResult.Available).version)
        assertEquals(2, server.requestCount) // Server WAS queried!

        // 4. Automatic check after 25 hours (past 24h) hits server again
        currentTimeMs += 25 * 60 * 60 * 1000L
        val latestReleaseJson = """
            {
                "tag_name": "v0.1.15",
                "html_url": "https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.15",
                "body": "25h later notes"
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(latestReleaseJson))

        val after24hResult = checker.checkForUpdates(force = false)
        assertTrue(after24hResult is AppUpdateResult.Available)
        assertEquals("v0.1.15", (after24hResult as AppUpdateResult.Available).version)
        assertEquals(3, server.requestCount) // Server WAS queried!
    }

    @Test
    fun throttledWithoutCachedUpdateReturnsThrottled() = runBlocking {
        // Pre-populate SharedPreferences with a check timestamp 1 hour ago and NO cached update
        fakePrefs.edit().putLong(AppUpdateChecker.KEY_LAST_CHECK_MS, currentTimeMs - 3600000L).apply()

        val checker = AppUpdateChecker(
            httpClient = OkHttpClient(),
            sharedPreferences = fakePrefs,
            apiUrl = server.url("/releases/latest").toString(),
            currentVersion = "0.1.12",
            clock = { currentTimeMs },
        )

        val result = checker.checkForUpdates(force = false)
        assertEquals(AppUpdateResult.Throttled, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun handles404OnLatestByFallingBackToReleasesList() = runBlocking {
        // 404 on /releases/latest
        server.enqueue(MockResponse().setResponseCode(404))
        // 200 on /releases (the fallback for prereleases)
        val listJson = """
            [
                {
                    "tag_name": "v0.1.13",
                    "html_url": "https://github.com/miguelraphael/ionic-auris/releases/tag/v0.1.13",
                    "body": "Fallback release notes"
                }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(listJson))

        val checker = AppUpdateChecker(
            httpClient = OkHttpClient(),
            sharedPreferences = fakePrefs,
            apiUrl = server.url("/releases/latest").toString(),
            currentVersion = "0.1.12",
            clock = { currentTimeMs },
        )

        val result = checker.checkForUpdates(force = true)
        assertTrue(result is AppUpdateResult.Available)
        assertEquals("v0.1.13", (result as AppUpdateResult.Available).version)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun handlesHttpErrorGracefully() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val checker = AppUpdateChecker(
            httpClient = OkHttpClient(),
            sharedPreferences = fakePrefs,
            apiUrl = server.url("/releases/latest").toString(),
            currentVersion = "0.1.12",
            clock = { currentTimeMs },
        )

        val result = checker.checkForUpdates(force = true)
        assertTrue(result is AppUpdateResult.Error)
        assertTrue((result as AppUpdateResult.Error).message.contains("500"))
    }

    @Test
    fun formatReleaseSnippetTruncatesLongTextWithEllipsis() {
        val short = "Auris 0.1.13 release."
        assertEquals(short, AppUpdateChecker.formatReleaseSnippet(short))

        val long = "A".repeat(300)
        val formatted = AppUpdateChecker.formatReleaseSnippet(long, maxLength = 100)
        assertNotNull(formatted)
        assertEquals(101, formatted!!.length) // 100 chars + '…'
        assertTrue(formatted.endsWith("…"))

        assertNull(AppUpdateChecker.formatReleaseSnippet(null))
        assertNull(AppUpdateChecker.formatReleaseSnippet("   \n   "))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        inner class Editor : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?) = apply { temp[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { temp[key!!] = values }
            override fun putInt(key: String?, value: Int) = apply { temp[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { temp[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { temp[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { temp[key!!] = value }
            override fun remove(key: String?) = apply { removed.add(key!!) }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) map.clear()
                for (k in removed) map.remove(k)
                map.putAll(temp)
            }
        }
    }
}
