package com.auris.app.data.remote.kitsu

import com.auris.app.core.model.MediaType

/**
 * Kitsu metadata for one title, normalised out of the anime/manga split.
 *
 * Extensions hand us titles scraped from fan sites; Kitsu returns clean localised titles, a
 * synopsis and artwork. This is the shape Auris stores and renders, with the union flattened
 * because `episodeCount` and `chapterCount` both mean "how many units does this have".
 */
data class KitsuMetadata(
    /** Kitsu's id. Stable, so it is what a library row stores instead of a title string. */
    val kitsuId: Long,
    val mediaType: MediaType,
    /**
     * Display title per [preferredLocale], falling back as far as needed.
     *
     * Nullable because Kitsu can return a record with neither `canonicalTitle` nor any usable
     * locale. The caller should then keep the title the extension supplied rather than
     * overwriting a good value with a blank.
     */
    val title: String?,
    /** The title in its original script — `ja_jp` where present. */
    val originalTitle: String?,
    /** Every locale Kitsu has. Kept because a fan-site match may be in any of them. */
    val titles: Map<String, String>,
    val synopsis: String?,
    /** 3:4 artwork for grids and cover surfaces. */
    val posterUrl: String?,
    /** Wide 4.2:1 artwork, suitable for the player's background gradient. */
    val coverUrl: String?,
    val status: KitsuStatus,
    /** `TV`, `Movie`, `OVA`, … for anime; `Manga`, `Light Novel`, `One Shot`, … for manga. */
    val subtype: String?,
    val averageRating: Double?,
    val startDate: String?,
    val ageRating: String?,
    val nsfw: Boolean,
    /** Episode count for anime, chapter count for manga. Null when the source omits it. */
    val unitCount: Int?,
)

/**
 * Kitsu's `status`, mapped onto Auris's vocabulary.
 *
 * Kitsu's values are lower snake_case (`on_hold`, not `ON_HOLD`), and `current` is the only
 * one that means ongoing. [UNKNOWN] is a real outcome rather than an error path: a record can
 * legitimately carry a status this client does not know, and guessing "unknown" is better than
 * showing the user a raw string.
 */
enum class KitsuStatus {
    ONGOING,
    FINISHED,
    ON_HIATUS,
    CANCELLED,
    PLANNED,
    UNKNOWN,
    ;

    companion object {
        fun fromApi(value: String?): KitsuStatus = when (value) {
            "current" -> ONGOING
            "finished" -> FINISHED
            "on_hold" -> ON_HIATUS
            "cancelled" -> CANCELLED
            "planned" -> PLANNED
            else -> UNKNOWN
        }
    }
}

/**
 * A scored search hit.
 *
 * ### Why scoring exists
 * `filter[text]` is a loose match. Searching `frieren` returns unrelated titles containing that
 * substring, and searching a romanisation Kitsu spells differently returns nothing at all.
 * Neither is a failure the API reports. Ranking has to happen here.
 *
 * [score] is deliberately explainable — see [KitsuMetadataMatcher] — rather than an opaque
 * number, because a wrong match is user-visible and the reason needs to be inspectable.
 */
data class KitsuMetadataMatch(
    val metadata: KitsuMetadata,
    /** Higher is better. See [KitsuMetadataMatcher] for the breakdown. */
    val score: Int,
    /** Human-readable reasons, surfaced in the picker UI so a wrong match is explainable. */
    val reasons: List<String>,
)

/**
 * Scores a Kitsu result against the title we searched for.
 *
 * ### Why not exact-match first
 * The common failure is a title Kitsu spells differently from the fan site: `Boku no Hero
 * Academia` vs `My Hero Academia`, `Sousou no Frieren` vs `Frieren: Beyond Journey's End`. An
 * exact-only strategy would discard the correct answer, and a substring-only strategy would
 * promote `Frieren: Beyond Journey's End (Dub)` over the real one. The score ranks all three.
 *
 * ### Normalisation
 * Punctuation, case and whitespace are stripped before comparing. `Re:Zero` and `Re Zero` and
 * `Re: Zero - Starting Life in Another World` should not fail to match because a colon and a
 * hyphen differ. Artifacts of scraping — trailing `(TV)`, ` (Dub)`, ` [Uncensored]` — are
 * dropped first.
 */
object KitsuMetadataMatcher {

    /** Score for an exact match after normalisation. Should always win. */
    const val SCORE_EXACT = 100

    /** Score for a match on one title with the other contained in it. */
    const val SCORE_CONTAINS = 60

    /** Score for a match after normalising artifacts and punctuation. */
    const val SCORE_NORMALISED = 40

    /** Score when only the leading token matches, e.g. `Frieren` vs `Frieren: ...`. */
    const val SCORE_TOKEN = 25

    /** Popularity bonus, capped so it can never outweigh a real title match. */
    const val MAX_POPULARITY_BONUS = 15

    /**
     * Shortest needle allowed to score a substring or leading-token match.
     *
     * One- and two-character queries are substrings of almost any title, so matching on them
     * produces confident-looking nonsense. Exact matches are still allowed at any length.
     */
    const val MIN_SUBSTRING_LENGTH = 3

    /**
     * Ranks [candidates] against [query].
     *
     * Returns them all with scores attached, sorted best-first — the caller decides how many to
     * show. Truncating here would hide the fact that the top hit was weak, which is exactly the
     * case worth surfacing.
     */
    fun rank(
        query: String,
        candidates: List<KitsuMetadata>,
        preferredLocale: String = "en",
    ): List<KitsuMetadataMatch> = candidates
        .map { candidate -> score(query, candidate, preferredLocale) }
        .sortedByDescending { it.score }

    /** Scores one candidate. Exposed so the picker can explain a single result. */
    fun score(
        query: String,
        candidate: KitsuMetadata,
        preferredLocale: String = "en",
    ): KitsuMetadataMatch {
        val reasons = mutableListOf<String>()

        val queryPlain = normalise(query)
        val queryStripped = stripArtifacts(query)

        // Every title Kitsu has, in preference order, so a match in any locale counts.
        // `title` is nullable (Kitsu can supply none) and blank entries are dropped so they
        // cannot produce a spurious empty-string match.
        val candidateTitles = buildList {
            candidate.titles[preferredLocale]?.let { add(it) }
            candidate.originalTitle?.let { add(it) }
            candidate.title?.let { add(it) }
            addAll(candidate.titles.values)
        }
            .filter { it.isNotBlank() }
            .map { it to normalise(it) }
            .distinctBy { it.second }

        var score = 0

        // Every textual rule below needs a non-empty needle. `String.contains("")` is true for
        // every haystack, so without this a blank query — or a candidate with a blank title —
        // would score a "contains" match against everything.
        val hasQuery = queryPlain.isNotEmpty()

        if (hasQuery && candidateTitles.any { it.second == queryPlain }) {
            score += SCORE_EXACT
            reasons += "exact title match"
        } else if (hasQuery && queryStripped.isNotEmpty() &&
            candidateTitles.any { it.second == queryStripped }
        ) {
            score += SCORE_NORMALISED
            reasons += "title match after removing (TV)/(Dub) markers"
        } else if (hasQuery && candidateTitles.any { pair ->
                pair.second.isNotEmpty() &&
                    (pair.second.contains(queryPlain) || queryPlain.contains(pair.second))
            }
        ) {
            // Guarded against a one-character query too: "a" is a substring of nearly every
            // title, so matching on it carries no signal.
            if (queryPlain.length >= MIN_SUBSTRING_LENGTH) {
                score += SCORE_CONTAINS
                reasons += "one title contains the other"
            }
        } else if (hasQuery && queryPlain.length >= MIN_SUBSTRING_LENGTH &&
            candidateTitles.any { pair ->
                pair.second.isNotEmpty() &&
                    pair.second.substringBefore(' ') == queryPlain.substringBefore(' ')
            }
        ) {
            score += SCORE_TOKEN
            reasons += "leading word matches"
        }

        // Popularity breaks ties between equally-good textual matches, but is deliberately
        // capped: a popular unrelated title must never outrank a correct match of an obscure
        // one. This is the difference between "suggested" and "wrong".
        candidate.averageRating?.let { rating ->
            val bonus = (rating / 100.0 * MAX_POPULARITY_BONUS).toInt()
            if (bonus > 0) {
                score += bonus
                reasons += "rating ${"%.1f".format(rating)}"
            }
        }

        return KitsuMetadataMatch(candidate, score, reasons)
    }

    /**
     * Lowercases and strips punctuation and whitespace.
     *
     * Unicode-aware: Kitsu titles are full of typographic punctuation (`’`, `：`, `‐`) and
     * Japanese script, so a plain `[^a-z0-9]` filter would empty half of them.
     */
    internal fun normalise(value: String): String = value
        .lowercase()
        .replace(NON_WORD, "")
        .trim()

    /**
     * Removes scraping artifacts before normalising.
     *
     * These suffixes appear constantly on extension sites and never in Kitsu titles:
     * `(TV)`, `(Dub)`, `(Uncensored)`, `[English Dub]`, `- Complete`.
     */
    internal fun stripArtifacts(value: String): String {
        var result = value
        ARTIFACT_SUFFIXES.forEach { result = result.replace(it, "") }
        ARTIFACT_SUFFIXES.forEach { result = result.replace(it, "") }
        return result.trim()
    }

    private val ARTIFACT_SUFFIXES = listOf(
        Regex("""\([^)]*\)"""),
        Regex("""\[[^\]]*]"""),
        Regex("""\s*[-–—]\s*(complete|uncensored)\s*$""", RegexOption.IGNORE_CASE),
    )

    /** Any Unicode letter, digit or mark; everything else is punctuation. */
    private val NON_WORD = Regex("""[^\p{L}\p{N}\p{M}]""")
}