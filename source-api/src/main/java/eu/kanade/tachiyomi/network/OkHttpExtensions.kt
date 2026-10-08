package eu.kanade.tachiyomi.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp coroutine helpers that extensions call from their request methods.
 *
 * ### Why a separate file, and not merely tidy organisation
 * Extensions link against the **file facade**, so this compiles to the JVM class
 * `OkHttpExtensionsKt`. Helpers placed in `RxInterop.kt` or `Requests.kt` belong to those files'
 * facades, and an extension calling into `OkHttpExtensionsKt` gets `NoClassDefFoundError` —
 * reported as a missing *class*, when what is actually missing is a file layout.
 *
 * ### The generic return type is part of the ABI
 * Real extensions call:
 *
 * ```
 * OkHttpExtensionsKt.awaitSuccess:(Lokhttp3/Call;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;
 * ```
 *
 * The descriptor returns `Object`, not `String` or `Response`, so the return type **must** be a
 * generic parameter. Declaring it concretely emits `()Ljava/lang/String;` and fails to link with
 * a `NoSuchMethodError` — the same class of mistake as narrowing `AnimeFilter.state`.
 *
 * The erasure is unavoidable, so the cast is unchecked and the real type is whatever the caller
 * expects. In practice extensions call it as `awaitSuccess<Response>()` or let it infer from the
 * surrounding `use { }`.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T> Call.awaitSuccess(): T = suspendCall() as T

/**
 * Executes this call, suspending until it completes.
 *
 * Cancellable, so cancelling playback cancels the in-flight request rather than letting it
 * complete into nothing.
 */
suspend fun Call.suspendCall(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })

    continuation.invokeOnCancellation {
        runCatching { cancel() }
    }
}
