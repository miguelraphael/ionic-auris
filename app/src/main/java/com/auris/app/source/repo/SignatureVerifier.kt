package com.auris.app.source.repo

/**
 * Whether an extension APK may be installed, given who claims to have signed it.
 *
 * ### The threat being defended against
 * An extension is a dex file that the app loads with a `DexClassLoader` and then calls into. It
 * runs with the app's full permissions — it can read the library database, the Supabase session
 * and the user's cookies. So "which APK is this really?" is a security question, not a
 * bookkeeping one, and a repository is a remote party that gets to choose what bytes it serves.
 *
 * Three separate things get confused here, and only the third is a signature:
 * - a **file hash** proves the bytes did not change (already stored, not sufficient on its own);
 * - **HTTPS** proves the transport was not tampered with (protects only while it is in flight);
 * - a **certificate fingerprint** proves who signed the bytes, and only the publisher can produce
 *   a matching certificate.
 *
 * ### Why pinning matters even when the repo declares a key
 * A repository that is compromised today can simply serve a different APK tomorrow. If the only
 * check were "does this match whatever the repo currently claims?", a compromised repo could
 * rotate its key, republish the index, and have the app accept anything. [Pinned] closes that:
 * the first fingerprint seen for a package is remembered, and later APKs must match it even if
 * the repo's claim changes.
 *
 * Rotation is still possible, but it becomes *visible* — the user sees that a known extension
 * changed signer and decides, rather than the change happening silently underneath them.
 */
sealed interface SignatureVerdict {

    /** Signed by the exact key the repo declares. */
    data object Trusted : SignatureVerdict

    /**
     * The repo declares no key, so this fingerprint was recorded on first sight.
     *
     * Trust on first use is a real weakness — it protects against a later swap but not against a
     * repo that is already hostile at install time. It is allowed because refusing would make
     * every user-supplied repository unusable, and it is surfaced as [UNVERIFIED] in the UI so it
     * is never silently treated as [Trusted].
     */
    data class Pinned(val fingerprint: String) : SignatureVerdict

    /**
     * Refused: the APK is unsigned, signed by a different key, or its pins conflict.
     *
     * Always a hard failure. There is no path from here to installing the APK.
     */
    data class Rejected(val reason: String) : SignatureVerdict

    /** A known companion to [Rejected], so the UI can explain itself without string matching. */
    val isInstallable: Boolean get() = this !is Rejected
}

/**
 * Decides trust from the repo's claim, the APK's certificates and what was pinned before.
 *
 * Stateless and pure, so the policy is testable on the JVM without a device, an APK or a
 * [PackageManager].
 */
object SignatureVerifier {

    /**
     * Normalises a fingerprint to lower-case hex with no separators.
     *
     * ### Why normalisation is not cosmetic
     * Repositories publish fingerprints in colon-separated upper case (`AB:CD:EF:…`) because
     * that is what keytool prints, while [ApkSigner] produces bare lower-case hex. Comparing the
     * raw strings would reject every real extension — and the failure mode is subtle, because
     * "fingerprint mismatch" is exactly what a genuine attack also looks like. A future reader
     * could easily "fix" such a mismatch report by loosening the comparison.
     *
     * Null or blank input normalises to null, meaning *no claim was made* — which is a different
     * thing from *a claim that did not match*.
     */
    fun normalize(fingerprint: String?): String? {
        val stripped = fingerprint?.filterNot { it == ':' || it == '-' || it.isWhitespace() }
        if (stripped.isNullOrEmpty()) return null

        // A fingerprint is a SHA-256: 64 hex characters. Rejecting other lengths here means a
        // malformed value cannot silently match a short or truncated hash.
        if (stripped.length != SHA256_HEX_LENGTH) return null
        if (!stripped.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null

        return stripped.lowercase()
    }

    /** Whether [candidate] is a well-formed fingerprint. Used to fail closed on bad config. */
    fun isWellFormed(fingerprint: String?): Boolean = normalize(fingerprint) != null

    /**
     * @param declaredFingerprint what the repository claims, or null/blank when it claims nothing
     * @param observed the APK's certificate fingerprints, from [ApkSigner]
     * @param previouslyPinned the fingerprint recorded for this package on an earlier install
     */
    fun verify(
        declaredFingerprint: String?,
        observed: Set<String>,
        previouslyPinned: String?,
    ): SignatureVerdict {
        if (observed.isEmpty()) {
            return SignatureVerdict.Rejected(
                "The APK is not signed, so its publisher cannot be identified",
            )
        }

        val declared = declaredFingerprint?.trim()?.takeIf { it.isNotEmpty() }
        val pinned = normalize(previouslyPinned)

        // A configured but malformed claim is a configuration error, and is refused rather than
        // downgraded to "unverified". Failing open here would mean a typo in a repo URL silently
        // disables verification for every extension from it.
        if (declared != null && !isWellFormed(declared)) {
            return SignatureVerdict.Rejected(
                "The repository's signing key fingerprint is malformed, so it cannot be checked",
            )
        }

        if (pinned != null) {
            // The pin wins over the declaration. A repo that starts claiming a different key
            // after the fact cannot roll a known extension onto a new signing key silently.
            return if (observed.contains(pinned)) {
                SignatureVerdict.Trusted
            } else {
                SignatureVerdict.Rejected(
                    "Signed by a different key than the one this extension was installed with",
                )
            }
        }

        val declaredNormalized = normalize(declared)
        return when {
            declaredNormalized == null -> {
                // Trust on first use: record what we saw so a later swap becomes visible.
                SignatureVerdict.Pinned(observed.first())
            }

            observed.contains(declaredNormalized) -> SignatureVerdict.Trusted

            else -> SignatureVerdict.Rejected(
                "Not signed by the key the repository declares",
            )
        }
    }

    /**
     * Compares two fingerprints without an early exit.
     *
     * Not a real defence here — there is no secret to leak and the attacker controls the timing of
     * nothing that matters in a local install — but it is cheap, and it removes the temptation
     * to write the tempting `==` version elsewhere. The length check does leak length, which is
     * fine because every valid fingerprint is the same length by construction.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (index in a.indices) {
            difference = difference or (a[index].code xor b[index].code)
        }
        return difference == 0
    }

    /** SHA-256 rendered as hex is 64 characters. */
    private const val SHA256_HEX_LENGTH = 64
}
