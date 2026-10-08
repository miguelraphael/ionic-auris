package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.nio.charset.Charset

/**
 * Parses a response body into a Jsoup document.
 *
 * ### On charsets
 * Extensions routinely serve shift_jis, windows-1252 and gb2312 pages. Decoding is left to
 * OkHttp, which picks the charset from `Content-Type` and falls back to a `<meta charset>`
 * sniff before treating the bytes as UTF-8 — so by the time [Response.body] yields a String
 * the characters are already correct, and Jsoup only has to parse a String.
 *
 * The declared charset is read here purely as a guard: it is validated so that a bogus
 * value surfaces as a loggable fact rather than an exception thrown from deep inside the
 * parser far away from the response that caused it.
 *
 * ### The `charset` parameter is part of the ABI
 * Extensions call `asJsoup(someCharset)`, which Kotlin compiles to
 * `asJsoup$default(Response, String, int, Object)`. A zero-parameter `asJsoup()` emits neither
 * that bridge nor a two-argument overload, so the call site fails with
 * `NoSuchMethodError: asJsoup$default`. Adding a defaulted parameter produces both descriptors
 * and keeps the no-argument call sites reading naturally.
 */
fun Response.asJsoup(charset: String? = null): Document {
    // The explicit charset, when the extension supplies one, wins over OkHttp's own detection —
    // a source that knows its site is shift_jis says so, and the header is often absent or
    // wrong on these hosts.
    val resolvedCharset = charset
        ?.takeIf { it.isNotBlank() }
        ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }

    // Resolve relative links against the request URL rather than the document's own base.
    val base = request.url.toString()
    val html = body?.let { responseBody ->
        resolvedCharset?.let { responseBody.byteStream().reader(it).readText() }
            ?: responseBody.string()
    } ?: throw IllegalStateException("Response body was null for $base")

    return Jsoup.parse(html, base)
}

/**
 * The charset named in `Content-Type`, or null when absent or unrecognised.
 *
 * OkHttp already applied it; this exists so callers and logs can report which encoding was
 * in play when a page renders as mojibake.
 */
private fun Response.declaredCharset(): Charset? {
    // OkHttp exposes repeated headers as a list; the first value is the effective one.
    val contentType = header("Content-Type") ?: return null

    val raw = contentType
        .substringAfter("charset=", "")
        .substringBefore(';')
        .trim()
        .removeSurrounding("\"")

    if (raw.isEmpty() || raw.length > 40) return null

    return runCatching { Charset.forName(raw) }.getOrNull()
}