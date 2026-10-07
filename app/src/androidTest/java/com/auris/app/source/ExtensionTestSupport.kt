package com.auris.app.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.DefaultNetworkHelper
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkHelperProvider
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Canned HTTP layer for extension tests.
 *
 * Extensions are exercised through their real parsing code, so the only thing stubbed is the
 * socket. Requests are matched by a substring of the URL and answered with fixture HTML; an
 * unmatched URL fails loudly rather than returning empty HTML, so a test can never
 * accidentally pass against a blank page.
 *
 * Installed through [NetworkHelperProvider], the same seam a production `NetworkHelper` uses —
 * nothing in the extension path knows it is under test.
 */
object FakeHttp {

    private val routes = mutableListOf<Route>()

    private data class Route(
        val urlContains: String,
        val body: String,
        val contentType: String,
    )

    /** Every request the extension made, in order. */
    val requestedUrls = mutableListOf<String>()

    /** Set to make every request fail, for error-path tests. */
    @Volatile
    var failureMessage: String? = null

    fun on(
        urlContains: String,
        body: String,
        contentType: String = "text/html; charset=utf-8",
    ) {
        routes += Route(urlContains, body, contentType)
    }

    fun reset() {
        routes.clear()
        requestedUrls.clear()
        failureMessage = null
    }

    fun networkHelper(): NetworkHelper = object : NetworkHelper {
        override val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(cannedInterceptor())
            .connectTimeout(2, TimeUnit.SECONDS)
            .build()

        override fun defaultUserAgentProvider(): String = "AurisTest/1.0"
    }

    private fun cannedInterceptor() = Interceptor { chain ->
        val url = chain.request().url.toString()
        requestedUrls += url

        failureMessage?.let { throw java.io.IOException(it) }

        val route = routes.firstOrNull { url.contains(it.urlContains) }
            ?: throw AssertionError(
                "No fake route registered for $url. Registered: ${routes.map { it.urlContains }}",
            )

        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", route.contentType)
            .body(route.body.toResponseBody(route.contentType.toMediaType()))
            .build()
    }
}

/**
 * Shared setup for tests that load a real extension APK through `DexClassLoader`.
 *
 * The APK is the artifact `:mock-extension:assembleDebug` produces, so the tests run against
 * genuine compiled dex rather than a hand-written stub.
 *
 * Note these are instrumented tests, not Robolectric: `DexClassLoader` needs a real dalvik VM
 * and has no usable JVM equivalent.
 */
abstract class ExtensionTestBase {

    protected lateinit var extensionLoader: ExtensionLoader

    @Before
    fun setUpExtensionEnvironment() {
        FakeHttp.reset()
        NetworkHelperProvider.install(FakeHttp.networkHelper())

        val context = ApplicationProvider.getApplicationContext<Context>()

        extensionLoader = ExtensionLoader(
            context = context,
            // Isolated per test so a stale optimized-dex directory cannot mask a failure.
            cacheRoot = File(
                context.cacheDir,
                "ext-test-${javaClass.simpleName}-${System.nanoTime()}",
            ),
        )
    }

    @After
    fun tearDownExtensionEnvironment() {
        extensionLoader.clearCache()
        extensionLoader.unloadAll()
        NetworkHelperProvider.reset()
        FakeHttp.reset()
    }

    /**
     * The mock extension APK.
     *
     * Installed as a test asset rather than looked up on disk: on an emulator the host
     * filesystem is not visible from the app sandbox, and `user.dir` is meaningless inside
     * instrumentation. Copying it into the app's own files dir is the only reliable way to
     * hand a real APK to `DexClassLoader`.
     */
    protected fun mockExtensionApk(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = File(context.filesDir, "mock-extension.apk")

        // Always re-copy. The app sandbox survives across test runs, so a cached copy would
        // silently keep testing a *previous* build of the extension after the mock changes —
        // which presents as "the loader ignores my source overrides".
        if (target.exists()) {
            target.delete()
        }

        val asset = "mock-extension.apk"
        runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets.open(asset)
                .use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            // DexClassLoader refuses a writable dex/jar: "Writable dex file is not
            // allowed." An installed extension APK is read-only for the same reason, so
            // the test copies must be too.
            target.setReadOnly()
            check(target.canRead()) { "Extension APK is not readable at $target" }
        }.onFailure { error ->
            error(
                "Could not copy $asset into the app sandbox. Build it first:\n" +
                    "  ./gradlew :mock-extension:assembleDebug\n" +
                    "Cause: ${error.message}",
            )
        }

        require(target.isFile && target.length() > 0) { "Extension APK missing at $target" }
        return target
    }

    protected fun mockExtensionPackage(): String = "com.auris.app.mockextension"

    /** A real `DefaultNetworkHelper`, for the one test that asserts host client policy. */
    protected fun defaultNetworkHelper(): NetworkHelper = DefaultNetworkHelper()
}