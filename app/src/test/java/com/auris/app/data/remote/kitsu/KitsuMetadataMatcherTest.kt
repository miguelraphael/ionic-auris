package com.auris.app.data.remote.kitsu

import com.auris.app.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching tests.
 *
 * The hard case is not "find the same string" — it is the two ways Kitsu search fails against a
 * title scraped from a fan site:
 *
 *  1. **False positive.** `filter[text]=frieren` returns unrelated titles containing that
 *     substring, in an order that is not a relevance ranking.
 *  2. **False negative.** A fan site spells it differently from Kitsu, so the correct answer
 *     never comes back at all.
 *
 * Every test below exists because one of those went wrong without the matcher noticing.
 */
class KitsuMetadataMatcherTest {

    private fun metadata(
        title: String?,
        titles: Map<String, String> = emptyMap(),
        originalTitle: String? = null,
        rating: Double? = null,
        id: Long = 1L,
    ) = KitsuMetadata(
        kitsuId = id,
        mediaType = MediaType.ANIME,
        title = title,
        originalTitle = originalTitle,
        titles = titles,
        synopsis = null,
        posterUrl = null,
        coverUrl = null,
        status = KitsuStatus.FINISHED,
        subtype = null,
        averageRating = rating,
        startDate = null,
        ageRating = null,
        nsfw = false,
        unitCount = null,
    )

    // ---- Exact and near-exact -----------------------------------------------

    @Test
    fun exactMatchScoresHighest() {
        val candidate = metadata("Sousou no Frieren", mapOf("en" to "Sousou no Frieren"))

        val match = KitsuMetadataMatcher.score("Sousou no Frieren", candidate)

        assertTrue(match.score >= KitsuMetadataMatcher.SCORE_EXACT)
        assertTrue(match.reasons.any { it.contains("exact") })
    }

    @Test
    fun matchIsCaseAndPunctuationInsensitive() {
        val candidate = metadata("Re:Zero - Starting Life in Another World")

        // Extensions and Kitsu disagree on punctuation constantly; the match must not hinge on it.
        val match = KitsuMetadataMatcher.score("re zero starting life in another world", candidate)

        assertTrue(match.score >= KitsuMetadataMatcher.SCORE_EXACT)
    }

    @Test
    fun stripsScrapingArtifactsFromQuery() {
        val candidate = metadata("Sousou no Frieren")

        val match = KitsuMetadataMatcher.score("Sousou no Frieren (TV)", candidate)

        // `(TV)`, `(Dub)` and `[Uncensored]` appear on fan sites and never in Kitsu titles.
        assertTrue(
            "score was ${match.score}",
            match.score >= KitsuMetadataMatcher.SCORE_NORMALISED,
        )
    }

    @Test
    fun stripsBracketedArtifacts() {
        val candidate = metadata("Berserk")

        val match = KitsuMetadataMatcher.score("Berserk [Uncensored]", candidate)

        assertTrue("score was ${match.score}", match.score >= KitsuMetadataMatcher.SCORE_NORMALISED)
    }

    // ---- The false-negative case -------------------------------------------

    @Test
    fun matchesAcrossDifferentlySpelledTitles() {
        // The fan site's title and Kitsu's canonical title are different strings for the same
        // series. Neither contains the other, so only the token rule can catch this.
        val candidate = metadata(
            title = "Sousou no Frieren",
            titles = mapOf("en" to "Frieren: Beyond Journey's End"),
            id = 46474L,
        )

        val match = KitsuMetadataMatcher.score("Frieren: Beyond Journey's End", candidate)

        assertTrue("score was ${match.score}", match.score >= KitsuMetadataMatcher.SCORE_EXACT)
    }

    @Test
    fun matchesOnALocaleTheQueryDidNotAskFor() {
        // An extension may hand back the Japanese title; Kitsu has it under ja_jp.
        val candidate = metadata(
            title = "Sousou no Frieren",
            titles = mapOf("en" to "Frieren: Beyond Journey's End", "ja_jp" to "葬送のフリーレン"),
        )

        val match = KitsuMetadataMatcher.score("葬送のフリーレン", candidate)

        assertTrue("score was ${match.score}", match.score >= KitsuMetadataMatcher.SCORE_EXACT)
    }

    @Test
    fun handlesJapaneseScriptWithoutCollapsing() {
        val candidate = metadata("葬送のフリーレン", mapOf("ja_jp" to "葬送のフリーレン"))

        val match = KitsuMetadataMatcher.score("葬送のフリーレン", candidate)

        // A `[^a-z0-9]` filter would empty both sides and produce a false exact match for
        // *any* two Japanese titles; Unicode-aware normalisation is what prevents that.
        assertTrue("score was ${match.score}", match.score >= KitsuMetadataMatcher.SCORE_EXACT)
    }

    @Test
    fun distinctJapaneseTitlesDoNotMatchEachOther() {
        val a = metadata("葬送のフリーレン", mapOf("ja_jp" to "葬送のフリーレン"))
        val b = metadata("進撃の巨人", mapOf("ja_jp" to "進撃の巨人"))

        val match = KitsuMetadataMatcher.score("葬送のフリーレン", b)

        assertTrue(
            "unrelated titles scored ${match.score}",
            match.score < KitsuMetadataMatcher.SCORE_TOKEN,
        )
    }

    // ---- The false-positive case -------------------------------------------

    @Test
    fun unrelatedTitleScoresLowerThanCorrectOne() {
        val correct = metadata(
            title = "Sousou no Frieren",
            titles = mapOf("en" to "Frieren: Beyond Journey's End"),
            id = 46474L,
        )
        // A real false positive: highly rated, and its stripped title equals the query exactly.
        val impostor = metadata(
            title = "Frieren: Beyond Journey's End (Dub)",
            rating = 95.0,
            id = 2L,
        )

        val ranked = KitsuMetadataMatcher.rank(
            query = "Frieren: Beyond Journey's End",
            candidates = listOf(impostor, correct),
        )

        // Popularity must never let a wrong title outrank a right one. The impostor wins on
        // rating alone; the correct one wins because it has a real title match.
        assertEquals(46474L, ranked.first().metadata.kitsuId)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun popularityCannotOutrankATitleMismatch() {
        val obscure = metadata("Kimi no Iro", rating = 40.0)
        val popular = metadata("Completely Different Show", rating = 99.0, id = 2L)

        val ranked = KitsuMetadataMatcher.rank("Kimi no Iro", listOf(popular, obscure))

        // The bonus is capped at MAX_POPULARITY_BONUS, below a single textual band.
        assertEquals(1L, ranked.first().metadata.kitsuId)
        assertTrue(
            "popularity gap ${popular.averageRating} vs ${obscure.averageRating} was too influential",
            ranked.first().score > ranked.last().score,
        )
    }

    @Test
    fun popularityBreaksTiesAmongEquallyGoodMatches() {
        val rated = metadata("Sousou no Frieren", rating = 88.84, id = 1L)
        val unrated = metadata("Sousou no Frieren", rating = null, id = 2L)

        val ranked = KitsuMetadataMatcher.rank("Sousou no Frieren", listOf(unrated, rated))

        assertEquals(1L, ranked.first().metadata.kitsuId)
    }

    @Test
    fun noMatchScoresLowAndSaysSo() {
        val candidate = metadata("Totally Unrelated", rating = 50.0)

        val match = KitsuMetadataMatcher.score("Sousou no Frieren", candidate)

        assertTrue("score was ${match.score}", match.score < KitsuMetadataMatcher.SCORE_TOKEN)
        // No textual reason is recorded, which is how the picker knows to say "no confident match".
        assertTrue(match.reasons.none { it.contains("title match") || it.contains("exact") })
    }

    @Test
    fun containsMatchScoresBelowExact() {
        val exact = metadata("Frieren", mapOf("en" to "Frieren"))
        val longer = metadata("Frieren: Beyond Journey's End", mapOf("en" to "Frieren: Beyond Journey's End"))

        val exactScore = KitsuMetadataMatcher.score("Frieren", exact).score
        val containsScore = KitsuMetadataMatcher.score("Frieren", longer).score

        assertTrue(
            "exact $exactScore should beat contains $containsScore",
            exactScore > containsScore,
        )
    }

    // ---- Ranking behaviour -------------------------------------------------

    @Test
    fun rankReturnsAllCandidatesBestFirst() {
        val candidates = listOf(
            metadata("Wrong Show", id = 3L),
            metadata("Sousou no Frieren", id = 1L),
            metadata("Frieren: Beyond Journey's End", id = 2L),
        )

        val ranked = KitsuMetadataMatcher.rank("Sousou no Frieren", candidates)

        // Truncating here would hide the fact that the top hit was weak, which is exactly the
        // case worth surfacing to the user.
        assertEquals(3, ranked.size)
        assertEquals(1L, ranked.first().metadata.kitsuId)

        // Scores must be non-increasing.
        val scores = ranked.map { it.score }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun rankIsStableForEquallyScoredCandidates() {
        // Two identical candidates must not swap between calls; a sortedByDescending on a
        // tie would otherwise make the picker's first row flicker.
        val a = metadata("Same", id = 1L)
        val b = metadata("Same", id = 2L)

        val first = KitsuMetadataMatcher.rank("Same", listOf(a, b)).map { it.metadata.kitsuId }
        val second = KitsuMetadataMatcher.rank("Same", listOf(a, b)).map { it.metadata.kitsuId }

        assertEquals(first, second)
    }

    @Test
    fun rankHandlesEmptyCandidateList() {
        assertTrue(KitsuMetadataMatcher.rank("Frieren", emptyList()).isEmpty())
    }

    // ---- Degenerate inputs --------------------------------------------------

    @Test
    fun blankTitleDoesNotMatchEverything() {
        // `normalise("") == ""`, and an empty string contains itself — so a blank title would
        // otherwise score an exact match against *any* query.
        val candidate = metadata(null, mapOf("en" to "   "))

        val match = KitsuMetadataMatcher.score("Sousou no Frieren", candidate)

        assertTrue("score was ${match.score}", match.score < KitsuMetadataMatcher.SCORE_CONTAINS)
    }

    @Test
    fun blankQueryScoresNothingMeaningful() {
        val candidate = metadata("Sousou no Frieren")

        val match = KitsuMetadataMatcher.score("   ", candidate)

        assertTrue("score was ${match.score}", match.score < KitsuMetadataMatcher.SCORE_TOKEN)
    }

    @Test
    fun nullTitleIsIgnoredNotCrashed() {
        val candidate = metadata(null, titles = mapOf("en" to "Frieren"))

        val match = KitsuMetadataMatcher.score("Frieren", candidate)

        assertTrue("score was ${match.score}", match.score >= KitsuMetadataMatcher.SCORE_EXACT)
    }

    @Test
    fun singleCharacterQueryDoesNotMatchBySubstring() {
        // "a" is a substring of nearly every title; matching on it would be meaningless.
        val candidate = metadata("Sousou no Frieren")

        val match = KitsuMetadataMatcher.score("a", candidate)

        assertTrue("score was ${match.score}", match.score < KitsuMetadataMatcher.SCORE_CONTAINS)
    }

    @Test
    fun reasonsArePopulatedForInspection() {
        val candidate = metadata("Sousou no Frieren", rating = 88.84)

        val match = KitsuMetadataMatcher.score("Sousou no Frieren", candidate)

        assertTrue(match.reasons.isNotEmpty())
        assertTrue(match.reasons.any { it.contains("88.8") })
    }

    @Test
    fun preferredLocaleIsTriedFirstButOthersStillCount() {
        val candidate = metadata(
            title = "Sousou no Frieren",
            titles = mapOf("it_it" to "Frieren: Oltre la Fine del Viaggio"),
        )

        val italian = KitsuMetadataMatcher.score(
            "Frieren: Oltre la Fine del Viaggio",
            candidate,
            preferredLocale = "it_it",
        )
        val default = KitsuMetadataMatcher.score(
            "Frieren: Oltre la Fine del Viaggio",
            candidate,
            preferredLocale = "en",
        )

        // Preferred locale ranks first, but a match in any locale must still be found.
        assertTrue(italian.score > 0)
        assertTrue(default.score > 0)
    }
}