package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client handed to extensions.
 *
 * Extensions reach this through [NetworkHelperProvider] rather than constructing their own
 * client, so that host policy (timeouts, interceptors, caching, User-Agent) applies to every
 * request an extension makes. An extension that builds its own client bypasses that policy,
 * which is why the accessor is part of the API.
 */
interface NetworkHelper {
    val client: OkHttpClient
    fun defaultUserAgentProvider(): String
}

/**
 * Process-wide holder for the [NetworkHelper].
 *
 * The host app installs the real instance at startup (see
 * `NetworkHelperProvider.install`). Extensions never write to this; they only read.
 */
object NetworkHelperProvider {

    @Volatile
    private var helper: NetworkHelper? = null

    /** Installs the helper. Must be called before any extension source is used. */
    fun install(instance: NetworkHelper) {
        helper = instance
    }

    /**
     * @throws IllegalStateException if the host forgot to install a helper. Failing loudly is
     * better than handing extensions a default client that silently ignores host policy.
     */
    fun get(): NetworkHelper = checkNotNull(helper) {
        "NetworkHelper has not been installed. Call NetworkHelperProvider.install() first."
    }

    /** Test seam: drop the installed helper. */
    fun reset() {
        helper = null
    }
}

/** Default implementation, owned by the host app. */
class DefaultNetworkHelper(
    override val client: OkHttpClient = defaultClient(),
) : NetworkHelper {

    override fun defaultUserAgentProvider(): String = DEFAULT_USER_AGENT

    companion object {
        /**
         * Sent as `User-Agent`. Real extensions are scraped from sites that reject unknown
         * agents outright, so this has to look like a browser rather than a library.
         */
        const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}