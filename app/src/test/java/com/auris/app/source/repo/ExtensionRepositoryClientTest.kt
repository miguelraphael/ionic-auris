package com.auris.app.source.repo

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ExtensionRepositoryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ExtensionRepositoryClient
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = ExtensionRepositoryClient(
            client = OkHttpClient(),
            json = json,
        )
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun testRepo(): ExtensionRepo = ExtensionRepo(
        baseUrl = server.url("/repo").toString(),
        name = "Test Repo",
    )

    private fun testRemoteExtension(): RemoteExtension = RemoteExtension(
        name = "Test Extension",
        packageName = "com.example.test",
        apkFileName = "test.apk",
        lang = "en",
        versionCode = 1,
        versionName = "1.0",
    )

    @Test
    fun fetchIndexParsesValidPayload(): Unit = runBlocking {
        val payload = """
            [
                {
                    "name": "Test Extension",
                    "pkg": "com.example.test",
                    "apk": "test.apk",
                    "lang": "en",
                    "code": 1,
                    "version": "1.0"
                }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(payload))

        val result = client.fetchIndex(testRepo())
        assertTrue(result.isSuccess)
        val extensions = result.getOrThrow()
        assertEquals(1, extensions.size)
        assertEquals("com.example.test", extensions.first().packageName)
    }

    @Test
    fun fetchIndexFailsOnOversizedPayload(): Unit = runBlocking {
        val oversizedSize = (ExtensionRepositoryClient.MAX_INDEX_BYTES + 1024).toInt()
        val buffer = Buffer()
        val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }
        var written = 0
        while (written < oversizedSize) {
            val toWrite = minOf(chunk.size, oversizedSize - written)
            buffer.write(chunk, 0, toWrite)
            written += toWrite
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(buffer))

        val result = client.fetchIndex(testRepo())
        assertTrue("Expected failure for index > 5MB", result.isFailure)
    }

    @Test
    fun fetchIndexFailsOnEmptyBody(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val result = client.fetchIndex(testRepo())
        assertTrue(result.isFailure)
    }

    @Test
    fun downloadApkSucceedsForValidSize(): Unit = runBlocking {
        val tempApk = File.createTempFile("ext-valid", ".apk").apply { delete() }
        tempApk.deleteOnExit()

        val validApkBytes = ByteArray(4096) { 0x50 } // > MIN_APK_BYTES (2048)
        val buffer = Buffer().write(validApkBytes)
        server.enqueue(MockResponse().setResponseCode(200).setBody(buffer))

        val result = client.downloadApk(testRemoteExtension(), testRepo(), tempApk)
        assertTrue(result.isSuccess)
        assertTrue(tempApk.exists())
        assertEquals(4096L, tempApk.length())
        tempApk.delete()
    }

    @Test
    fun downloadApkFailsAndDeletesFileWhenTooSmall(): Unit = runBlocking {
        val tempApk = File.createTempFile("ext-small", ".apk").apply { delete() }
        tempApk.deleteOnExit()

        val tinyBytes = ByteArray(100) { 0x11 } // < MIN_APK_BYTES
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(tinyBytes)))

        val result = client.downloadApk(testRemoteExtension(), testRepo(), tempApk)
        assertTrue(result.isFailure)
        assertFalse("Destination file should be deleted on failure", tempApk.exists())
    }

    @Test
    fun downloadApkFailsAndDeletesFileWhenExceedingMaxSize(): Unit = runBlocking {
        val tempApk = File.createTempFile("ext-large", ".apk").apply { delete() }
        tempApk.deleteOnExit()

        val oversizedSize = ExtensionRepositoryClient.MAX_APK_BYTES + 4096
        val buffer = Buffer()
        val chunk = ByteArray(1024 * 1024) { 0x22 }
        var written = 0L
        while (written < oversizedSize) {
            val toWrite = minOf(chunk.size.toLong(), oversizedSize - written).toInt()
            buffer.write(chunk, 0, toWrite)
            written += toWrite
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(buffer))

        val result = client.downloadApk(testRemoteExtension(), testRepo(), tempApk)
        assertTrue(result.isFailure)
        assertFalse("Destination file should be deleted on exceeding MAX_APK_BYTES", tempApk.exists())
    }

    @Test
    fun fetchIconReturnsBytesWhenWithinLimit(): Unit = runBlocking {
        val iconBytes = ByteArray(1024) { 0x33 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(iconBytes)))

        val result = client.fetchIcon(server.url("/icon.png").toString())
        assertEquals(1024, result?.size)
    }

    @Test
    fun fetchIconReturnsNullWhenExceedingLimit(): Unit = runBlocking {
        val oversizedSize = (ExtensionRepositoryClient.MAX_ICON_BYTES + 1024).toInt()
        val buffer = Buffer()
        val chunk = ByteArray(64 * 1024) { 0x33 }
        var written = 0
        while (written < oversizedSize) {
            val toWrite = minOf(chunk.size, oversizedSize - written)
            buffer.write(chunk, 0, toWrite)
            written += toWrite
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(buffer))

        val result = client.fetchIcon(server.url("/huge-icon.png").toString())
        assertNull(result)
    }
}
