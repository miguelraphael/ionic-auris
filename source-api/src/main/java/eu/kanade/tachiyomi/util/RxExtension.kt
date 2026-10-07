package eu.kanade.tachiyomi.util

import rx.Observable
import java.io.IOException

/**
 * Awaits a single value from an [Observable].
 *
 * ### Why this exists
 * Anikku's `AnimeSource` expresses the primary methods in terms of RxJava 1:
 *
 * ```kotlin
 * suspend fun getAnimeDetails(anime: SAnime): SAnime = fetchAnimeDetails(anime).awaitSingle()
 * ```
 *
 * Extensions override the `fetch*` methods, and the interface's default bodies reference
 * `awaitSingle`. A host that omits it throws `NoSuchMethodError` the first time any
 * inherited method runs, so this is part of the compatibility surface, not a convenience.
 *
 * ### Semantics
 * Blocks until the first element arrives, then unsubscribes. A stream that errors propagates
 * the error; one that completes empty raises [NoSuchElementException]. Both match
 * `awaitSingle` upstream.
 */
suspend fun <T> Observable<T>.awaitSingle(): T = unwrapRx { toBlocking().single() }

/** Suspends until the first element arrives, or fails the coroutine. */
suspend fun <T> Observable<T>.awaitFirst(): T = unwrapRx { toBlocking().first() }

/** [awaitFirst] with a fallback used when the stream completes empty. */
suspend fun <T> Observable<T>.awaitFirstOrDefault(default: T): T =
    unwrapRx { toBlocking().firstOrDefault(default) }

/**
 * RxJava 1 wraps any non-`RuntimeException` error from a blocking call in a fresh
 * `RuntimeException`, so an `IOException` from the network layer would reach the caller as a
 * bare `RuntimeException` with no type information. Callers need to distinguish "the source
 * failed" from "the source returned nothing", so the original throwable is unwrapped and
 * rethrown as-is.
 */
private inline fun <T> unwrapRx(block: () -> T): T = try {
    block()
} catch (e: RuntimeException) {
    val cause = e.cause
    if (cause is IOException) throw cause
    if (cause is RuntimeException && cause !== e) throw cause
    throw e
}

/** Awaits completion of a stream that yields no value. */
@Suppress("UNCHECKED_CAST")
suspend fun <T> Observable<T>.await(): Unit {
    toBlocking().firstOrDefault(Unit as T)
}

/** Subscribes and immediately unsubscribes, for fire-and-forget use. */
fun <T> Observable<T>.blockingAwait(): Unit {
    subscribe().unsubscribe()
}