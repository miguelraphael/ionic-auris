package com.auris.app.data.remote.kitsu

import com.auris.app.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapper tests.
 *
 * The unit of correctness here is "does a sparse or absent field produce the right *kind* of
 * wrong" — null rather than zero, false rather than default. A `unitCount` of 0 reads as "no
 * episodes" in the UI, and blanking a title reads as a broken row.
 */
class KitsuMapperTest {

    private fun anime(
        canonicalTitle: String? = "Sousou no Frieren",
        titles: KitsuTitles? = null,
        episodeCount: Int? = 28,
        status: String? = "finished",
        poster: KitsuImage? = null,
        cover: KitsuImage? = null,
        nsfw: Boolean? = null,
        synopsis: String? = "Summary.",
    ) = KitsuAnimeAttributes(
        canonicalTitle = canonicalTitle,
        titles = titles,
        synopsis = synopsis,
        status = status,
        ageRating = "PG",
        averageRating = 88.84,
        startDate = "2023-09-29",
        episodeCount = episodeCount,
        posterImage = poster,
        coverImage = cover,
        nsfw = nsfw,
    )

    // ---- Happy path ---------------------------------------------------------

    @Test
    fun mapsAnimeToMetadata() {
        val result = KitsuMapper.toMetadata(
            id = 46474L,
            attributes = anime(
                titles = KitsuTitles(
                    en = "Frieren: Beyond Journey's End",
                    jaJp = "葬送のフリーレン",
                ),
                poster = KitsuImage(large = "https://img.test/poster.jpeg"),
                cover = KitsuImage(large = "https://img.test/cover.jpeg"),
            ),
        )!!

        assertEquals(46474L, result.kitsuId)
        assertEquals(MediaType.ANIME, result.mediaType)
        // canonicalTitle wins: it is what Kitsu itself displays.
        assertEquals("Sousou no Frieren", result.title)
        assertEquals("葬送のフリーレン", result.originalTitle)
        assertEquals("Summary.", result.synopsis)
        assertEquals("https://img.test/poster.jpeg", result.posterUrl)
        assertEquals("https://img.test/cover.jpeg", result.coverUrl)
        assertEquals(KitsuStatus.FINISHED, result.status)
        assertEquals("PG", result.ageRating)
        assertEquals(88.84, result.averageRating!!, 0.001)
        assertEquals("2023-09-29", result.startDate)
        assertEquals(28, result.unitCount)
    }

    @Test
    fun mapsMangaWithChapterCountAsUnitCount() {
        val result = KitsuMapper.toMetadata(
            id = 8L,
            attributes = KitsuMangaAttributes(
                canonicalTitle = "Berserk",
                titles = KitsuTitles(en = "Berserk", jaJp = "ベルセルク"),
                status = "on_hold",
                chapterCount = 374,
                nsfw = true,
            ),
        )!!

        assertEquals(MediaType.MANGA, result.mediaType)
        assertEquals(374, result.unitCount)
        assertEquals(KitsuStatus.ON_HIATUS, result.status)
        assertTrue(result.nsfw)
    }

    @Test
    fun mediaTypeIsAlwaysCorrect() {
        // Anime and manga are mapped by separate functions precisely so this cannot drift.
        val animeResult = KitsuMapper.toMetadata(1L, anime())
        val mangaResult = KitsuMapper.toMetadata(1L, KitsuMangaAttributes(canonicalTitle = "X"))

        assertEquals(MediaType.ANIME, animeResult!!.mediaType)
        assertEquals(MediaType.MANGA, mangaResult!!.mediaType)
    }

    // ---- Status -------------------------------------------------------------

    @Test
    fun mapsEveryKitsuStatus() {
        assertEquals(KitsuStatus.ONGOING, KitsuStatus.fromApi("current"))
        assertEquals(KitsuStatus.FINISHED, KitsuStatus.fromApi("finished"))
        assertEquals(KitsuStatus.ON_HIATUS, KitsuStatus.fromApi("on_hold"))
        assertEquals(KitsuStatus.CANCELLED, KitsuStatus.fromApi("cancelled"))
        assertEquals(KitsuStatus.PLANNED, KitsuStatus.fromApi("planned"))
    }

    @Test
    fun unknownStatusFallsBackRatherThanThrowing() {
        // Kitsu can add a status; guessing "unknown" beats showing the user a raw string.
        assertEquals(KitsuStatus.UNKNOWN, KitsuStatus.fromApi("hiatus_forever"))
        assertEquals(KitsuStatus.UNKNOWN, KitsuStatus.fromApi(null))
    }

    // ---- Missing data -------------------------------------------------------

    @Test
    fun missingUnitCountIsNullNotZero() {
        val result = KitsuMapper.toMetadata(1L, anime(episodeCount = null))!!

        // 0 would render as "0 episodes", which is a different claim from "unknown".
        assertNull(result.unitCount)
    }

    @Test
    fun missingTitleIsNullSoCallerKeepsTheExtensionTitle() {
        val result = KitsuMapper.toMetadata(1L, anime(canonicalTitle = null, titles = null))!!

        // The caller should fall back to the extension's own title rather than overwrite it
        // with a blank.
        assertNull(result.title)
    }

    @Test
    fun titleFallsBackToAnyAvailableLocale() {
        val result = KitsuMapper.toMetadata(
            1L,
            anime(canonicalTitle = null, titles = KitsuTitles(jaJp = "葬送のフリーレン")),
        )!!

        assertEquals("葬送のフリーレン", result.title)
    }

    @Test
    fun blankCanonicalTitleFallsThrough() {
        val result = KitsuMapper.toMetadata(
            1L,
            anime(canonicalTitle = "   ", titles = KitsuTitles(en = "Real Title")),
        )!!

        assertEquals("Real Title", result.title)
    }

    @Test
    fun synopsisFallsBackToDescription() {
        val result = KitsuMapper.toMetadata(
            1L,
            anime(synopsis = null).copy(description = "From description."),
        )!!

        assertEquals("From description.", result.synopsis)
    }

    @Test
    fun blankSynopsisIsTreatedAsAbsent() {
        val result = KitsuMapper.toMetadata(
            1L,
            anime(synopsis = "   ").copy(description = "From description."),
        )!!

        // A whitespace synopsis would render as an empty-looking description block.
        assertEquals("From description.", result.synopsis)
    }

    @Test
    fun missingImagesAreNull() {
        val result = KitsuMapper.toMetadata(1L, anime(poster = null, cover = null))!!

        assertNull(result.posterUrl)
        assertNull(result.coverUrl)
    }

    @Test
    fun blankStartDateIsTreatedAsAbsent() {
        val result = KitsuMapper.toMetadata(1L, anime().copy(startDate = ""))!!

        assertNull(result.startDate)
    }

    @Test
    fun absentNsfwFlagIsFalse() {
        val result = KitsuMapper.toMetadata(1L, anime(nsfw = null))!!

        // Absent means not flagged, which is the only safe reading for an adult-content gate.
        assertTrue(!result.nsfw)
    }

    @Test
    fun largeSnowflakeIdIsStoredExactly() {
        // Kitsu ids are 64-bit. Truncating one to Int — or back to a String and re-parsing —
        // would store a wrong id that resolves to a different title or to nothing.
        val bigId = 8_000_000_000_000_000_001L

        val result = KitsuMapper.toMetadata(bigId, anime())!!

        assertEquals(bigId, result.kitsuId)
        assertEquals(bigId.toString(), bigId.toString())
    }

    // ---- Titles map ---------------------------------------------------------

    @Test
    fun flattensTitlesKeepingLocaleKeys() {
        val result = KitsuMapper.toMetadata(
            1L,
            anime(
                titles = KitsuTitles(
                    en = "Frieren",
                    enJp = "Sousou no Frieren",
                    jaJp = "葬送のフリーレン",
                    koKr = "   ",
                ),
            ),
        )!!

        assertEquals("Frieren", result.titles["en"])
        assertEquals("葬送のフリーレン", result.titles["ja_jp"])
        // A locale present with a blank value would outrank a real value in a fallback chain.
        assertTrue(!result.titles.containsKey("ko_kr"))
        assertEquals(3, result.titles.size)
    }

    @Test
    fun originalTitleFallsBackToRomanisedJapanese() {
        val result = KitsuMapper.toMetadata(
            1L,
            anime(titles = KitsuTitles(enJp = "Sousou no Frieren")),
        )!!

        assertEquals("Sousou no Frieren", result.originalTitle)
    }

    @Test
    fun absentTitlesMapToEmptyMapNotNull() {
        val result = KitsuMapper.toMetadata(1L, anime(titles = null))!!

        assertTrue(result.titles.isEmpty())
    }

    // ---- Preferred locale ---------------------------------------------------

    @Test
    fun preferredLocaleOverridesCanonicalTitle() {
        val result = KitsuMapper.toMetadata(
            id = 1L,
            attributes = anime(
                canonicalTitle = null,
                titles = KitsuTitles(en = "English", jaJp = "日本語"),
            ),
            preferredLocale = "ja_jp",
        )!!

        assertEquals("日本語", result.title)
    }
}