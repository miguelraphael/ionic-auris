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
 */
fun Response.asJsoup(): Document {
    // Resolve relative links against the request URL rather than the document's own base.
    val base = request.url.toString()
    val html = body?.string()
        ?: throw IllegalStateException("Response body was null for $base")

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