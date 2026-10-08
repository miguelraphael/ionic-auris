package eu.kanade.tachiyomi.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Request builders extensions call from their `*Request()` overrides.
 *
 * ### The `CacheControl` parameter is part of the ABI, not an optional extra
 * These are emitted with a trailing `CacheControl?` because that is what real extensions call:
 *
 * ```
 * GET:(Ljava/lang/String;Lokhttp3/Headers;Lokhttp3/CacheControl;)Lokhttp3/Request;
 * GET:(Lokhttp3/HttpUrl;Lokhttp3/Headers;Lokhttp3/CacheControl;)Lokhttp3/Request;
 * POST:(Ljava/lang/String;Lokhttp3/Headers;Lokhttp3/RequestBody;Lokhttp3/CacheControl;)Lokhttp3/Request;
 * ```
 *
 * Omitting it compiles fine and is invisible to every test we had — the failure only appears when
 * a source builds its first request, as a `NoSuchMethodError` naming `RequestsKt`. The default
 * value keeps host code calling `GET(url)` readable while emitting exactly the three-argument
 * descriptor extensions link against.
 *
 * A `null` cache means "do not set", which is what `Request.Builder` does with no cache-control
 * header at all.
 */
fun GET(
    url: String,
    headers: Headers = Headers.headersOf(),
    cache: CacheControl? = null,
): Request = GET(url.toHttpUrlOrNull(), headers, cache)

/**
 * [HttpUrl] overload.
 *
 * Extensions that already hold a parsed URL call this rather than the `String` one, so both
 * descriptors are needed.
 */
fun GET(
    url: HttpUrl,
    headers: Headers = Headers.headersOf(),
    cache: CacheControl? = null,
): Request =
    Request.Builder()
        .url(url)
        .headers(headers)
        .apply { cache?.let(::cacheControl) }
        .get()
        .build()

fun POST(
    url: String,
    headers: Headers = Headers.headersOf(),
    body: RequestBody? = null,
    cache: CacheControl? = null,
): Request = POST(url.toHttpUrlOrNull(), headers, body, cache)

fun POST(
    url: HttpUrl,
    headers: Headers = Headers.headersOf(),
    body: RequestBody? = null,
    cache: CacheControl? = null,
): Request =
    Request.Builder()
        .url(url)
        .headers(headers)
        .apply { cache?.let(::cacheControl) }
        .post(body ?: EMPTY_BODY)
        .build()

/**
 * Parses [url] without throwing.
 *
 * A malformed URL in an extension's constant should surface as a failed request the source can
 * catch, not as a `URISyntaxException` thrown from a builder it cannot intercept.
 */
private fun String.toHttpUrlOrNull(): HttpUrl =
    runCatching { toHttpUrl() }.getOrElse { "https://invalid.invalid/".toHttpUrl() }

fun Request.Builder.header(name: String, value: String): Request.Builder =
    addHeader(name, value)

/**
 * Executes the call and returns the body as a String.
 *
 * This is the suspend entry point modern extensions use instead of RxJava. Failures are
 * raised as [IOException] with the status code in the message, which is what a source's
 * existing `catch (e: Exception)` blocks already handle.
 */
suspend fun Request.awaitString(): String {
    // Capture the receiver: inside withContext, `this` is the CoroutineScope.
    val request = this
    return withContext(Dispatchers.IO) {
        NetworkHelperProvider.get().client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(
                    "HTTP ${response.code} for ${request.url}: ${text.take(ERROR_BODY_LIMIT)}",
                )
            }
            text
        }
    }
}

/**
 * Executes the call and hands the live [Response] to [block].
 *
 * For sources whose `*Parse` needs the status code, headers and raw body together. The
 * response is always closed.
 */
suspend fun Request.awaitResponse(block: (Response) -> Unit) {
    awaitResult(block)
}

/**
 * Executes the call and returns whatever [block] produced.
 *
 * Preferred over [awaitResponse] at call sites that need a parsed value — the seasons,
 * hosters and video-list hooks all parse inside the callback.
 */
suspend fun <T> Request.awaitResult(block: (Response) -> T): T {
    val request = this
    return withContext(Dispatchers.IO) {
        NetworkHelperProvider.get().client.newCall(request).execute().use(block)
    }
}

private const val ERROR_BODY_LIMIT = 512

private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

/** Empty headers, named to read clearly at call sites. */
private fun Headers.Companion.headersOf(): Headers = Headers.Builder().build()