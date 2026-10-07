package com.auris.app.data.remote.kitsu

import com.auris.app.core.model.MediaType

/**
 * Maps Kitsu resource objects onto [KitsuMetadata].
 *
 * ### Anime and manga are mapped separately on purpose
 * They share most fields, but `episodeCount` and `chapterCount` are different facts, and a
 * shared function with a nullable-for-both result would let `unitCount` be null for a title
 * that plainly has 28 episodes — indistinguishable from "unknown". Two functions make the
 * distinction impossible to get wrong at the call site.
 *
 * ### Absent attributes
 * `canonicalTitle`, `synopsis` and both images can all be missing on a real record, so every
 * read is nullable and the fallbacks are explicit rather than `.orEmpty()` producing a blank
 * title that would silently overwrite a good one from the extension.
 */
object KitsuMapper {

    /**
     * Maps an anime.
     *
     * @param id Kitsu's id, taken from [KitsuResource.id] rather than from the attributes —
     *   `id` is a sibling of `attributes` in JSON:API and is easy to lose. This is the value
     *   persisted as `library_entries.kitsu_id`.
     */
    fun toMetadata(
        id: Long,
        attributes: KitsuAnimeAttributes,
        preferredLocale: String = "en",
    ): KitsuMetadata? {
        return KitsuMetadata(
            kitsuId = id,
            mediaType = MediaType.ANIME,
            title = resolveTitle(attributes.canonicalTitle, attributes.titles, preferredLocale),
            originalTitle = attributes.titles?.resolve("ja_jp") ?: attributes.titles?.resolve("en_jp"),
            titles = attributes.titles.toMap(),
            // `description` is the same text as `synopsis` plus credits; `synopsis` is the
            // clean field, so it is preferred and `description` is only a backstop.
            synopsis = attributes.synopsis?.takeIf { it.isNotBlank() }
                ?: attributes.description?.takeIf { it.isNotBlank() },
            posterUrl = attributes.posterImage?.url(KitsuImage.LARGE),
            coverUrl = attributes.coverImage?.url(KitsuImage.LARGE),
            status = KitsuStatus.fromApi(attributes.status),
            subtype = attributes.subtype,
            averageRating = attributes.averageRating,
            startDate = attributes.startDate?.takeIf { it.isNotBlank() },
            ageRating = attributes.ageRating,
            nsfw = attributes.nsfw == true,
            unitCount = attributes.episodeCount,
        )
    }

    /** Maps a manga. `unitCount` is the chapter count, never an episode count. */
    fun toMetadata(
        id: Long,
        attributes: KitsuMangaAttributes,
        preferredLocale: String = "en",
    ): KitsuMetadata? {
        return KitsuMetadata(
            kitsuId = id,
            mediaType = MediaType.MANGA,
            title = resolveTitle(attributes.canonicalTitle, attributes.titles, preferredLocale),
            originalTitle = attributes.titles?.resolve("ja_jp") ?: attributes.titles?.resolve("en_jp"),
            titles = attributes.titles.toMap(),
            synopsis = attributes.synopsis?.takeIf { it.isNotBlank() }
                ?: attributes.description?.takeIf { it.isNotBlank() },
            posterUrl = attributes.posterImage?.url(KitsuImage.LARGE),
            coverUrl = attributes.coverImage?.url(KitsuImage.LARGE),
            status = KitsuStatus.fromApi(attributes.status),
            subtype = attributes.subtype,
            averageRating = attributes.averageRating,
            startDate = attributes.startDate?.takeIf { it.isNotBlank() },
            ageRating = attributes.ageRating,
            nsfw = attributes.nsfw == true,
            unitCount = attributes.chapterCount,
        )
    }

    /**
     * The display title.
     *
     * `canonicalTitle` first: Kitsu uses it as the romanised default and it is what the site
     * itself displays. `titles.best` then tries the preferred locale and falls back through
     * `en_jp` to anything present, because a record with only `ja_jp` still has a usable title.
     *
     * Returns null rather than an empty string when nothing is available — callers should keep
     * the extension's own title in that case rather than blanking it.
     */
    private fun resolveTitle(
        canonical: String?,
        titles: KitsuTitles?,
        preferredLocale: String,
    ): String? {
        canonical?.takeIf { it.isNotBlank() }?.let { return it }
        return titles?.best(preferredLocale, "en_jp", "ja_jp")?.takeIf { it.isNotBlank() }
    }

    /**
     * Flattens [KitsuTitles] into a locale-keyed map.
     *
     * Built by listing keys explicitly rather than from [KitsuTitles.asOrderedList], which
     * returns only the values and so would lose which locale each one came from. Blank titles
     * are dropped: a locale present with an empty string is worse than absent, because it
     * would win a fallback over a real value.
     */
    private fun KitsuTitles?.toMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap {
            putIfPresent("en", en); putIfPresent("en_jp", enJp); putIfPresent("en_us", enUs)
            putIfPresent("ja_jp", jaJp); putIfPresent("es", es); putIfPresent("es_la", esLa)
            putIfPresent("pt_br", ptBr); putIfPresent("pt_pt", ptPt); putIfPresent("fr", fr)
            putIfPresent("it", it); putIfPresent("it_it", itIt); putIfPresent("de", de)
            putIfPresent("ko", ko); putIfPresent("ko_kr", koKr); putIfPresent("zh_cn", zhCn)
            putIfPresent("zh_tw", zhTw); putIfPresent("ru", ru); putIfPresent("th_th", thTh)
        }
    }

    private fun MutableMap<String, String>.putIfPresent(key: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { put(key, it) }
    }
}