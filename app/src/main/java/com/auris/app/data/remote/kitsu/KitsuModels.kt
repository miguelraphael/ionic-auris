package com.auris.app.data.remote.kitsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kitsu's `anime` attributes.
 *
 * ### Every field is optional with a null default
 * Not defensive programming — a *verified* property of the API. A manga and an anime share
 * this shape but not this field set (`manga` has `chapterCount`, `anime` has `episodeCount`),
 * Kitsu adds attributes without a migration, and sparse records genuinely omit keys. Declaring
 * any field as non-null would turn one missing key into a failed parse of the whole page.
 */
@Serializable
data class KitsuAnimeAttributes(
    @SerialName("canonicalTitle") val canonicalTitle: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
    @SerialName("abbreviatedTitles") val abbreviatedTitles: List<String>? = null,
    @SerialName("synopsis") val synopsis: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("slug") val slug: String? = null,
    /** `TV`, `Movie`, `OVA`, `ONA`, `Special`, `Music`. Not an enum upstream. */
    @SerialName("subtype") val subtype: String? = null,
    /** `current`, `finished`, `on_hold`, `planned`, `cancelled`. */
    @SerialName("status") val status: String? = null,
    /** `PG`, `PG-13`, `R`, `R18+`. */
    @SerialName("ageRating") val ageRating: String? = null,
    @SerialName("ageRatingGuide") val ageRatingGuide: String? = null,
    @SerialName("averageRating") val averageRating: Double? = null,
    @SerialName("popularityRank") val popularityRank: Int? = null,
    @SerialName("favoritesCount") val favoritesCount: Int? = null,
    @SerialName("startDate") val startDate: String? = null,
    @SerialName("endDate") val endDate: String? = null,
    @SerialName("episodeCount") val episodeCount: Int? = null,
    @SerialName("episodeLength") val episodeLength: Int? = null,
    @SerialName("posterImage") val posterImage: KitsuImage? = null,
    @SerialName("coverImage") val coverImage: KitsuImage? = null,
    @SerialName("youtubeVideoId") val youtubeVideoId: String? = null,
    @SerialName("nsfw") val nsfw: Boolean? = null,
)

/**
 * Kitsu's `manga` attributes.
 *
 * Deliberately a separate type rather than a shared one with nullable fields: `chapterCount`
 * has no meaning for an anime and `episodeCount` none for a manga, so a merged type would let
 * a caller read a field that can only ever be null. Fields common to both are repeated — the
 * duplication is the type doing its job.
 */
@Serializable
data class KitsuMangaAttributes(
    @SerialName("canonicalTitle") val canonicalTitle: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
    @SerialName("abbreviatedTitles") val abbreviatedTitles: List<String>? = null,
    @SerialName("synopsis") val synopsis: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("subtype") val subtype: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("ageRating") val ageRating: String? = null,
    @SerialName("ageRatingGuide") val ageRatingGuide: String? = null,
    @SerialName("averageRating") val averageRating: Double? = null,
    @SerialName("popularityRank") val popularityRank: Int? = null,
    @SerialName("favoritesCount") val favoritesCount: Int? = null,
    @SerialName("startDate") val startDate: String? = null,
    @SerialName("endDate") val endDate: String? = null,
    @SerialName("chapterCount") val chapterCount: Int? = null,
    @SerialName("volumeCount") val volumeCount: Int? = null,
    @SerialName("posterImage") val posterImage: KitsuImage? = null,
    @SerialName("coverImage") val coverImage: KitsuImage? = null,
    @SerialName("nsfw") val nsfw: Boolean? = null,
)

/**
 * Localised titles, keyed by Kitsu's locale codes.
 *
 * Kitsu returns whichever locales exist for a record and no more — Frieren came back with
 * `en`, `en_jp`, `it_it`, `ja_jp`, `ko_kr`, `pt_br`, `th_th`, `zh_cn`; Berserk with only
 * `en`, `en_jp`, `en_us`, `ja_jp`. So the key set varies per title and callers must not index
 * a fixed locale. [best] applies a preference order instead.
 */
@Serializable
data class KitsuTitles(
    @SerialName("en") val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
    @SerialName("en_us") val enUs: String? = null,
    @SerialName("ja_jp") val jaJp: String? = null,
    @SerialName("ja_ro") val jaRo: String? = null,
    @SerialName("es") val es: String? = null,
    @SerialName("es_la") val esLa: String? = null,
    @SerialName("pt_br") val ptBr: String? = null,
    @SerialName("pt_pt") val ptPt: String? = null,
    @SerialName("fr") val fr: String? = null,
    @SerialName("it") val it: String? = null,
    @SerialName("it_it") val itIt: String? = null,
    @SerialName("de") val de: String? = null,
    @SerialName("ko") val ko: String? = null,
    @SerialName("ko_kr") val koKr: String? = null,
    @SerialName("zh_cn") val zhCn: String? = null,
    @SerialName("zh_tw") val zhTw: String? = null,
    @SerialName("ru") val ru: String? = null,
    @SerialName("th_th") val thTh: String? = null,
) {
    /**
     * All present titles, in Kitsu's own locale order.
     *
     * Used when no caller preference applies and for the fallback chain in [best].
     */
    fun asOrderedList(): List<String> = listOfNotNull(
        en, enJp, enUs, jaJp, jaRo, es, esLa, ptBr, ptPt, fr, it, itIt, de, ko, koKr, zhCn, zhTw,
        ru, thTh,
    ).filter { it.isNotBlank() }

    /**
     * The title closest to [locale], then [fallbacks], then anything at all.
     *
     * Extensions hand us a title scraped from a site that may be Japanese, English or a fan
     * romanisation, so a two-key lookup is not enough; the ordered fallback list is what makes
     * a partial match useful.
     */
    fun best(locale: String = "en", vararg fallbacks: String): String? =
        resolve(locale) ?: fallbacks.firstNotNullOfOrNull { resolve(it) } ?: asOrderedList().firstOrNull()

    /**
     * One locale's title, or null when absent or blank.
     *
     * `internal` because the mapper reads `ja_jp`/`en_jp` directly to populate
     * [KitsuMetadata.originalTitle]; an unknown key also returns null rather than throwing.
     */
    internal fun resolve(key: String): String? = when (key) {
        "en" -> en
        "en_jp" -> enJp
        "en_us" -> enUs
        "ja_jp" -> jaJp
        "es" -> es
        "es_la" -> esLa
        "pt_br" -> ptBr
        "pt_pt" -> ptPt
        "fr" -> fr
        "it" -> it
        "it_it" -> itIt
        "de" -> de
        "ko" -> ko
        "ko_kr" -> koKr
        "zh_cn" -> zhCn
        "zh_tw" -> zhTw
        "ru" -> ru
        "th_th" -> thTh
        else -> null
    }?.takeIf { it.isNotBlank() }
}

/**
 * An image and its variants.
 *
 * `posterImage` is roughly 3:4 and `coverImage` roughly 4.2:1, so they are not
 * interchangeable: the poster is the grid/cover-art surface and the cover is the wide
 * background for the player-style gradient. [meta.dimensions] is a nested map keyed by the
 * same variant names.
 */
@Serializable
data class KitsuImage(
    @SerialName("tiny") val tiny: String? = null,
    @SerialName("small") val small: String? = null,
    @SerialName("medium") val medium: String? = null,
    @SerialName("large") val large: String? = null,
    @SerialName("original") val original: String? = null,
    @SerialName("meta") val meta: KitsuImageMeta? = null,
) {
    /**
     * The best available URL at or above [preferredSize].
     *
     * Falls back *down* when the preferred size is missing rather than up, because a 110px
     * thumbnail blown up to full screen is worse than a slightly small large. Returns null only
     * when the record genuinely has no image.
     */
    fun url(preferredSize: String = LARGE): String? = when (preferredSize) {
        LARGE -> large ?: original ?: medium ?: small ?: tiny
        MEDIUM -> medium ?: large ?: small ?: tiny ?: original
        SMALL -> small ?: tiny ?: medium ?: large ?: original
        else -> large ?: medium ?: small ?: tiny ?: original
    }

    /**
     * Width/height for a variant, when Kitsu reports them.
     *
     * Useful for choosing a variant that fills a layout without upscaling: asking for `large`
     * on a screen 1080px wide needs `original` instead.
     */
    fun dimensions(size: String = LARGE): Pair<Int, Int>? =
        meta?.dimensions?.get(size)?.let { it.width to it.height }

    companion object {
        const val TINY = "tiny"
        const val SMALL = "small"
        const val MEDIUM = "medium"
        const val LARGE = "large"
        const val ORIGINAL = "original"
    }
}

/** Image pixel dimensions, keyed by variant name. */
@Serializable
data class KitsuImageMeta(
    @SerialName("dimensions") val dimensions: Map<String, KitsuDimensions> = emptyMap(),
)

@Serializable
data class KitsuDimensions(
    @SerialName("width") val width: Int = 0,
    @SerialName("height") val height: Int = 0,
)

/**
 * A Kitsu genre.
 *
 * Genres are **not** usefully embedded in an anime or manga response. `relationships.genres`
 * exists, but its `data` is absent unless requested, and `include=genres` was observed against
 * a live title returning an empty relationship array. The genre endpoint is the reliable
 * source, so genre ids come from [KitsuClient.genres] rather than from a title lookup.
 */
@Serializable
data class KitsuGenre(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String = "genres",
    @SerialName("attributes") val attributes: KitsuGenreAttributes,
)

@Serializable
data class KitsuGenreAttributes(
    @SerialName("name") val name: String? = null,
    @SerialName("slug") val slug: String? = null,
    @SerialName("description") val description: String? = null,
)