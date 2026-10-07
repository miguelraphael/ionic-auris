package eu.kanade.tachiyomi.network

import okhttp3.Response
import rx.Observable
import rx.Single

/**
 * Adapts an OkHttp call to an RxJava 1 stream.
 *
 * Anikku's `AnimeHttpSource` is written as
 * `client.newCall(request).asObservableSuccess().map { parse(it) }`, so extensions inherit
 * these helpers through the base class's default bodies. Without them the inherited
 * `fetchPopularAnime` throws `NoSuchMethodError` the first time a source is used.
 */
fun okhttp3.Call.asObservableSuccess(): Observable<Response> = asObservable()

/** The call as a stream. */
fun okhttp3.Call.asObservable(): Observable<Response> = Observable.create { subscriber ->
    val callback = object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            subscriber.onError(e)
        }

        override fun onResponse(call: okhttp3.Call, response: Response) {
            // The body is closed by `use` in the consumer; a failure to parse surfaces as an
            // onError rather than being swallowed.
            try {
                subscriber.onNext(response)
                subscriber.onCompleted()
            } catch (e: Throwable) {
                subscriber.onError(e)
            }
        }
    }

    try {
        enqueue(callback)
    } catch (e: Throwable) {
        subscriber.onError(e)
    }
}

/**
 * The call as a single-value stream that fails on a non-2xx status.
 *
 * Mirrors Anikku's `awaitSuccess`, which throws for unsuccessful responses rather than
 * handing a 404 body to the parser.
 */
fun okhttp3.Call.awaitSuccess(): Observable<Response> = Observable.create { subscriber ->
    val callback = object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
            subscriber.onError(e)
        }

        override fun onResponse(call: okhttp3.Call, response: Response) {
            if (response.isSuccessful) {
                subscriber.onNext(response)
                subscriber.onCompleted()
            } else {
                subscriber.onError(
                    java.io.IOException("HTTP ${response.code} for ${call.request().url}"),
                )
                response.close()
            }
        }
    }

    try {
        enqueue(callback)
    } catch (e: Throwable) {
        subscriber.onError(e)
    }
}

/** Bridges a Kotlin value to a single-item Rx stream, for `rx` interop in tests. */
fun <T> singleOf(value: T): Single<T> = Single.just(value)