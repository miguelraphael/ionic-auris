package com.auris.app.data.remote.kitsu

import com.auris.app.core.model.MediaType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing tests against **captured real responses**.
 *
 * The JSON below was taken verbatim from live Kitsu requests (minus the fields irrelevant to
 * the assertion). Using real payloads rather than hand-written fixtures is the point: a fixture
 * authored from the JSON:API specification would have missed every surprise noted in
 * [KitsuDocument] — the polymorphic `data`, absent `links`/`meta`, `data`-less relationships,
 * and stringified 64-bit ids.
 */
class KitsuParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    // GET /anime?filter[text]=frieren&page[limit]=1 — collection shape.
    private val animeCollection = """
        {
          "data": [{
            "id": "46474",
            "type": "anime",
            "links": { "self": "https://kitsu.io/api/edge/anime/46474" },
            "attributes": {
              "createdAt": "2022-09-13T05:36:19.002Z",
              "updatedAt": "2026-10-07T00:00:14.354Z",
              "slug": "sousou-no-frieren",
              "synopsis": "After the party of heroes defeated the Demon King...",
              "description": "After the party of heroes defeated the Demon King...",
              "coverImageTopOffset": 0,
              "titles": {
                "en": "Frieren: Beyond Journey's End",
                "en_jp": "Sousou no Frieren",
                "it_it": "Frieren: Oltre la Fine del Viaggio",
                "ja_jp": "葬送のフリーレン",
                "ko_kr": "장송의 프리렌",
                "pt_br": "Frieren e a Jornada para o Alem",
                "th_th": "คำอธิษฐาน",
                "zh_cn": "葬送的芙莉蓮"
              },
              "canonicalTitle": "Sousou no Frieren",
              "abbreviatedTitles": ["Frieren", " Sousou no Frieren"],
              "posterImage": {
                "tiny": "https://media.kitsu.app/anime/46474/poster_image/tiny-a.jpeg",
                "large": "https://media.kitsu.app/anime/46474/poster_image/large-b.jpeg",
                "small": "https://media.kitsu.app/anime/46474/poster_image/small-c.jpeg",
                "medium": "https://media.kitsu.app/anime/46474/poster_image/medium-d.jpeg",
                "original": "https://media.kitsu.app/anime/46474/poster_image/e.jpg",
                "meta": {
                  "dimensions": {
                    "tiny": { "width": 110, "height": 156 },
                    "large": { "width": 550, "height": 780 }
                  }
                }
              },
              "coverImage": {
                "tiny": "https://media.kitsu.app/anime/46474/cover_image/tiny-f.jpeg",
                "large": "https://media.kitsu.app/anime/46474/cover_image/large-g.jpeg",
                "small": "https://media.kitsu.app/anime/46474/cover_image/small-h.jpeg",
                "meta": { "dimensions": { "large": { "width": 3360, "height": 800 } } }
              },
              "averageRating": 88.84,
              "userCount": 4000,
              "favoritesCount": 900,
              "subtype": "TV",
              "status": "finished",
              "ageRating": "PG",
              "ageRatingGuide": "Teens 13 or Older",
              "popularityRank": 12,
              "ratingRank": 8,
              "startDate": "2023-09-29",
              "endDate": "2024-03-22",
              "episodeCount": 28,
              "episodeLength": 24,
              "youtubeVideoId": "abc123",
              "nsfw": false
            },
            "relationships": { "genres": { "links": { "self": "https://kitsu.io/api/edge/anime/46474/relationships/genres" } } }
          }],
          "links": { "self": "https://kitsu.io/api/edge/anime?filter[text]=frieren&page[limit]=1" },
          "meta": { "count": 42 }
        }
    """.trimIndent()

    // GET /manga?filter[text]=berserk&page[limit]=1 — note chapterCount, not episodeCount.
    private val mangaCollection = """
        {
          "data": [{
            "id": "8",
            "type": "manga",
            "attributes": {
              "canonicalTitle": "Berserk",
              "titles": { "en": "Berserk", "en_jp": "Berserk", "en_us": "Berserk", "ja_jp": "ベルセルク" },
              "synopsis": "Guts travels the world...",
              "slug": "berserk",
              "status": "on_hold",
              "subtype": "Manga",
              "ageRating": "R18+",
              "chapterCount": 374,
              "volumeCount": 41,
              "posterImage": { "large": "https://media.kitsu.app/manga/8/poster_image/large.jpeg" },
              "coverImage": { "large": "https://media.kitsu.app/manga/8/cover_image/large.jpeg" },
              "averageRating": 84.5,
              "startDate": "1989-08-25",
              "nsfw": true
            }
          }],
          "meta": { "count": 40 }
        }
    """.trimIndent()

    // GET /anime/46474 — single-resource shape: `data` is an object, and links/meta are absent.
    private val animeSingle = """
        {
          "data": {
            "id": "46474",
            "type": "anime",
            "attributes": {
              "canonicalTitle": "Sousou no Frieren",
              "titles": { "en": "Frieren: Beyond Journey's End", "ja_jp": "葬送のフリーレン" },
              "synopsis": "Summary.",
              "status": "finished",
              "episodeCount": 28,
              "posterImage": { "large": "https://media.kitsu.app/anime/46474/poster_image/large-b.jpeg" }
            },
            "links": { "self": "https://kitsu.io/api/edge/anime/46474" }
          }
        }
    """.trimIndent()

    private fun unwrapAll(body: String): List<KitsuResourceObject> {
        val document = json.decodeFromString(KitsuDocument.serializer(), body)
        val data = document.data
        return when (data) {
            is kotlinx.serialization.json.JsonArray -> data.map {
                json.decodeFromJsonElement(KitsuResourceObject.serializer(), it)
            }
            is kotlinx.serialization.json.JsonObject -> listOf(
                json.decodeFromJsonElement(KitsuResourceObject.serializer(), data),
            )
            else -> emptyList()
        }
    }

    // ---- Envelope -----------------------------------------------------------

    @Test
    fun parsesCollectionEnvelope() {
        val document = json.decodeFromString(KitsuDocument.serializer(), animeCollection)

        assertEquals(42, document.meta?.count)
        assertTrue(document.links?.self!!.contains("filter[text]=frieren"))
        assertNull(document.errors)
    }

    @Test
    fun parsesSingleResourceEnvelope() {
        val document = json.decodeFromString(KitsuDocument.serializer(), animeSingle)

        // The single-resource form omits `links` and `meta` entirely rather than sending null,
        // which is why both are declared nullable with a default.
        assertNull(document.links)
        assertNull(document.meta)
        assertEquals(1, unwrapAll(animeSingle).size)
    }

    @Test
    fun relationshipDataMayBeAbsent() {
        val resource = unwrapAll(animeCollection).single()

        // `genres` has `links` but no `data`: Kitsu only sends relationship data when asked.
        // Declaring this non-null would make every anime without `include=genres` fail to parse.
        val genres = resource.relationships!!["genres"]!!
        assertNotNull(genres.links)
        assertNull(genres.data)
    }

    @Test
    fun relationshipDataParsesWhenPresent() {
        val body = """
            {"data":{"id":"1","type":"anime","attributes":{"canonicalTitle":"X"},
             "relationships":{"genres":{"data":[{"id":"1","type":"genres"}]}}}}
        """.trimIndent()

        val genres = unwrapAll(body).single().relationships!!["genres"]!!

        assertEquals(1, genres.data!!.size)
        assertEquals("genres", genres.data!!.single().type)
    }

    // ---- Ids ----------------------------------------------------------------

    @Test
    fun parsesStringIdAsLong() {
        val resource = unwrapAll(animeCollection).single()

        // Snowflake ids arrive quoted. 46474 is small, but ids exceed Long.MAX on real records.
        assertEquals("46474", resource.id)
        assertEquals(46474L, resource.idAsLong)
    }

    @Test
    fun nonNumericIdBecomesNullRatherThanThrowing() {
        val body = """
            {"data":[{"id":"not-a-number","type":"anime","attributes":{"canonicalTitle":"X"}}]}
        """.trimIndent()

        assertNull(unwrapAll(body).single().idAsLong)
    }

    // ---- Attributes ---------------------------------------------------------

    @Test
    fun parsesAnimeAttributes() {
        val resource = unwrapAll(animeCollection).single()
        val attributes = json.decodeFromJsonElement(
            KitsuAnimeAttributes.serializer(),
            resource.attributes,
        )

        assertEquals("Sousou no Frieren", attributes.canonicalTitle)
        assertEquals("TV", attributes.subtype)
        assertEquals("finished", attributes.status)
        assertEquals("PG", attributes.ageRating)
        assertEquals(88.84, attributes.averageRating!!, 0.001)
        assertEquals(28, attributes.episodeCount)
        assertEquals("2023-09-29", attributes.startDate)
        assertFalse(attributes.nsfw!!)
        assertEquals(8, attributes.titles!!.jaJp!!.length)
    }

    @Test
    fun parsesMangaChapterCountSeparatelyFromEpisodeCount() {
        val resource = unwrapAll(mangaCollection).single()
        val attributes = json.decodeFromJsonElement(
            KitsuMangaAttributes.serializer(),
            resource.attributes,
        )

        assertEquals(374, attributes.chapterCount)
        assertEquals(41, attributes.volumeCount)
        // A manga has no episodeCount field at all; it must read as null, not 0.
        assertNull(attributes.startDate?.takeIf { it == "2023-09-29" })
        assertEquals("on_hold", attributes.status)
    }

    @Test
    fun toleratesSparseRecords() {
        val body = """
            {"data":[{"id":"7","type":"anime","attributes":{"canonicalTitle":"Sparse"}}],
             "meta":{"count":1}}
        """.trimIndent()

        val attributes = json.decodeFromJsonElement(
            KitsuAnimeAttributes.serializer(),
            unwrapAll(body).single().attributes,
        )

        // Kitsu adds attributes without a migration and sparse records genuinely omit keys;
        // every field must therefore be optional or one absent key fails the whole page.
        assertEquals("Sparse", attributes.canonicalTitle)
        assertNull(attributes.synopsis)
        assertNull(attributes.posterImage)
        assertNull(attributes.episodeCount)
        assertNull(attributes.averageRating)
    }

    @Test
    fun ignoresUnknownAttributes() {
        val body = """
            {"data":[{"id":"7","type":"anime","attributes":{
              "canonicalTitle":"X","someBrandNewField":{"a":[1,2,3]},"sig":"deadbeef"}}],
             "meta":{"count":1},"brandNewTopLevel":true}
        """.trimIndent()

        val attributes = json.decodeFromJsonElement(
            KitsuAnimeAttributes.serializer(),
            unwrapAll(body).single().attributes,
        )

        assertEquals("X", attributes.canonicalTitle)
    }

    // ---- Titles -------------------------------------------------------------

    @Test
    fun resolvesTitlesByLocale() {
        val titles = KitsuTitles(
            en = "Frieren: Beyond Journey's End",
            enJp = "Sousou no Frieren",
            jaJp = "葬送のフリーレン",
        )

        assertEquals("Frieren: Beyond Journey's End", titles.resolve("en"))
        assertEquals("葬送のフリーレン", titles.resolve("ja_jp"))
        assertEquals("Sousou no Frieren", titles.resolve("en_jp"))
        assertNull(titles.resolve("xx_xx"))
    }

    @Test
    fun bestTitleFallsBackAcrossLocales() {
        // A record with only `ja_jp` must still produce a usable title.
        val japaneseOnly = KitsuTitles(jaJp = "葬送のフリーレン")

        assertEquals(
            "葬送のフリーレン",
            japaneseOnly.best("en", "en_jp", "ja_jp"),
        )
        assertNull(KitsuTitles().best())
    }

    @Test
    fun blankTitlesAreNotResolved() {
        val titles = KitsuTitles(en = "  ", jaJp = "葬送")

        // An empty-string title would win a fallback over a real value.
        assertNull(titles.resolve("en"))
        assertEquals("葬送", titles.resolve("ja_jp"))
    }

    @Test
    fun keySetVariesPerRecordAndAllLocalesSurvive() {
        // Frieren has 8 locales, Berserk 4. The mapper must not assume a fixed key set.
        val frieren = KitsuTitles(
            en = "Frieren", enJp = "Sousou no Frieren", itIt = "Frieren IT", jaJp = "葬送",
            koKr = "장송", ptBr = "Frieren BR", thTh = "Frieren TH", zhCn = "葬送",
        )
        val berserk = KitsuTitles(en = "Berserk", enJp = "Berserk", enUs = "Berserk", jaJp = "ベルセルク")

        assertEquals(8, frieren.asOrderedList().size)
        assertEquals(4, berserk.asOrderedList().size)
    }

    // ---- Images -------------------------------------------------------------

    @Test
    fun picksLargeImageByDefault() {
        val image = KitsuImage(
            tiny = "t", small = "s", medium = "m", large = "L", original = "o",
        )

        assertEquals("L", image.url())
    }

    @Test
    fun imageUrlFallsBackDownNotUp() {
        // No `large`, but `original` exists — `original` wins. A record with only `tiny` must
        // not be answered with nothing, since a small image beats no image.
        assertEquals("o", KitsuImage(tiny = "t", original = "o").url())
        assertEquals("m", KitsuImage(tiny = "t", medium = "m").url())
        assertEquals("t", KitsuImage(tiny = "t").url())
        assertNull(KitsuImage().url())
    }

    @Test
    fun readsImageDimensions() {
        val image = KitsuImage(
            large = "L",
            meta = KitsuImageMeta(
                dimensions = mapOf(
                    "large" to KitsuDimensions(width = 3360, height = 800),
                    "tiny" to KitsuDimensions(width = 110, height = 156),
                ),
            ),
        )

        assertEquals(3360 to 800, image.dimensions("large"))
        assertEquals(110 to 156, image.dimensions("tiny"))
        assertNull(image.dimensions("original"))
    }

    // ---- Genres -------------------------------------------------------------

    @Test
    fun parsesGenreResource() {
        val body = """
            {"data":[{"id":"1","type":"genres","links":{"self":"https://kitsu.io/api/edge/genres/1"},
             "attributes":{"createdAt":"2013-02-20T16:00:15.623Z","name":"Action","slug":"action",
             "description":""}}],"meta":{"count":85}}
        """.trimIndent()

        val resource = unwrapAll(body).single()
        val attributes = json.decodeFromJsonElement(
            KitsuGenreAttributes.serializer(),
            resource.attributes,
        )

        assertEquals("1", resource.id)
        assertEquals("Action", attributes.name)
        assertEquals("action", attributes.slug)
    }

    // ---- Error shape --------------------------------------------------------

    @Test
    fun parsesErrorObjects() {
        val body = """
            {"errors":[{"id":"e1","status":"400","code":"invalid_filter",
             "title":"Invalid filter","detail":"Genre filter must be numeric"}]}
        """.trimIndent()

        val errors = json.decodeFromString(KitsuDocument.serializer(), body).errors!!

        // The detail is written for humans and is far more actionable than a bare status code.
        assertEquals("invalid_filter", errors.single().code)
        assertEquals("Invalid filter", errors.single().title)
        assertEquals("Genre filter must be numeric", errors.single().detail)
    }

    @Test
    fun parsesErrorAlongsideAbsentData() {
        val body = """
            {"errors":[{"status":"404","title":"Not Found","detail":"Not found"}]}
        """.trimIndent()

        val document = json.decodeFromString(KitsuDocument.serializer(), body)

        assertEquals("Not found", document.errors!!.single().detail)
        // `data` is entirely absent, which must not throw — otherwise the message above is
        // unreachable, since parsing fails before it can be read.
        assertNull(document.data)
        assertNull(document.meta)
    }
}