package eu.kanade.tachiyomi.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/**
 * Request builders extensions call from their `*Request()` overrides.
 *
 * Extensions were compiled against Aniyomi's `eu.kanade.tachiyomi.network.GET` / `POST`
 * helpers, so these must keep the same names and parameter order.
 */
fun GET(url: String, headers: Headers = Headers.headersOf()): Request =
    Request.Builder()
        .url(url)
        .headers(headers)
        .get()
        .build()

fun POST(
    url: String,
    headers: Headers = Headers.headersOf(),
    body: RequestBody? = null,
): Request =
    Request.Builder()
        .url(url)
        .headers(headers)
        .post(body ?: EMPTY_BODY)
        .build()

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