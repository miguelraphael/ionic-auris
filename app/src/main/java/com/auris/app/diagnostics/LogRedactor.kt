package com.auris.app.diagnostics

/**
 * Masks anything that looks like a credential in exported log text.
 *
 * ### Why this is a separate, dependency-free object
 * The exported bundle leaves the device through a share sheet — into a chat app, a bug tracker,
 * an email. Anything unredacted is effectively published. That makes redaction the one part of
 * the exporter that must be provably correct, so it is:
 *
 *  - **pure** — string in, string out, no Android or filesystem dependency, so it is testable on
 *    the JVM with no device and no Robolectric;
 *  - **testable in isolation** — see `LogRedactionTest`;
 *  - **explicit** — [redact] is called at the one place text enters the bundle.
 *
 * ### The trade-off it makes
 * Over-redacting costs a debugging round trip. Under-redacting leaks a credential. Where those
 * conflict the code errs toward redaction, but deliberately *not* for things that merely look
 * secret: extension package names, class names and file paths are preserved, because scrubbing
 * them would leave stack traces and the extension list useless — which is most of the file.
 */
object LogRedactor {

    /**
     * Each entry pairs a pattern with the capture group to **preserve**; the rest of the match
     * is replaced with the mask.
     *
     * The distinction matters and is easy to get wrong. For `Bearer <token>` the useful part is
     * the scheme — knowing *which* auth scheme failed is the diagnostic value — so group 1 is
     * kept. For a bare JWT or a Supabase URL there is no such prefix: group 1 **is** the secret,
     * so preserving it would emit the credential followed by `[redacted]`, which is no redaction
     * at all. Hence `keepGroup = 0` for those, meaning "preserve nothing".
     */
    private data class Rule(val pattern: Regex, val keepGroup: Int)

    private val RULES: List<Rule> = listOf(
        // `Bearer <jwt>` — the commonest leaked credential in a crash log.
        Rule(Regex("""((?i:bearer)\s+)[A-Za-z0-9\-._~+/]{8,}=*"""), keepGroup = 1),

        // `Cookie: session=…` / `Cookie=…`
        Rule(Regex("""((?i:cookie)\s*[:=]\s*)\S+"""), keepGroup = 1),

        // Supabase project URLs. The ref is not itself a secret, but it pairs with any key that
        // leaked beside it, so the whole URL is masked.
        Rule(Regex("""https?://[a-z0-9-]+\.supabase\.co""", RegexOption.IGNORE_CASE), keepGroup = 0),

        // A bare JWT (`eyJ…`, three base64url segments) with nothing labelling it. Libraries
        // print raw values often enough that keying on "apikey=" alone is not enough.
        Rule(Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"""), keepGroup = 0),
    )

    private const val MASK = "[redacted]"

    /** Returns [line] with any credential-shaped substring masked. */
    fun redact(line: String): String {
        if (line.isEmpty()) return line

        var result = line
        RULES.forEach { rule ->
            result = rule.pattern.replace(result) { match ->
                val keep = if (rule.keepGroup == 0) "" else match.groupValues[rule.keepGroup]
                "$keep$MASK"
            }
        }
        return result
    }
}