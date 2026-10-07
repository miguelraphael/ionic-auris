package com.auris.app.data.remote.kitsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The JSON:API 1.0 envelope Kitsu speaks.
 *
 * ### Why hand-rolled rather than a JSON:API library
 * Kitsu's responses are JSON:API, but the shape has one trap that off-the-shelf serializers
 * get wrong or cannot express: **`data` is polymorphic**. A single resource
 * (`/anime/46474`) returns an object; a collection (`/anime?filter[...]`) returns an array.
 * A `List<ResourceObject>` declaration parses the second and throws on the first.
 *
 * [KitsuDocument.data] holds it as a `JsonElement` and the kind is resolved on demand, which
 * is why [KitsuClient] has one decode path rather than one per endpoint shape.
 *
 * ### Verified against the live API
 * Field names below were read from real responses, not from the specification:
 *  - top level is exactly `{ data, links?, included?, errors?, meta? }`;
 *  - `links` and `meta` are **absent**, not null, on some responses;
 *  - `relationships.<name>` contains `links` always, but `data` only when the relationship is
 *    requested — an unrelated anime returned `{"genres": {"links": {...}}}` with no `data`.
 */
@Serializable
data class KitsuDocument(
    /**
     * Object for a single resource, array for a collection.
     *
     * Typed as [JsonElement] rather than a sealed hierarchy: the distinction is a JSON shape,
     * not a domain one, and [KitsuClient.unwrap] collapses it to a list immediately.
     *
     * **Nullable with a null default.** Kitsu error responses carry only `errors` and omit
     * `data` altogether. A non-null declaration would fail the parse with
     * `MissingFieldException`, losing the very error message the caller needs to report —
     * verified against a real 404 body.
     */
    @SerialName("data") val data: JsonElement? = null,
    @SerialName("included") val included: List<KitsuResourceObject>? = null,
    @SerialName("links") val links: KitsuLinks? = null,
    @SerialName("meta") val meta: KitsuMeta? = null,
    @SerialName("errors") val errors: List<KitsuError>? = null,
)

/**
 * A resource object: `type`, `id`, `attributes`, `relationships`.
 *
 * ### `id` is a String in the wire format
 * Kitsu serialises ids as JSON strings (`"id": "46474"`) even though they are 64-bit
 * Snowflake values. Declaring this as `Long` and letting kotlinx coerce would work for the
 * happy path, but a malformed id would fail the *whole* response rather than one record, so
 * the raw string is kept and [idAsLong] converts per-resource. A single unparsable id then
 * skips one entry instead of dropping a page of results.
 */
@Serializable
data class KitsuResourceObject(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("attributes") val attributes: JsonElement,
    @SerialName("relationships") val relationships: Map<String, KitsuRelationship>? = null,
    @SerialName("links") val links: KitsuResourceLinks? = null,
) {
    /** The id as a number, or null when the server sent something non-numeric. */
    val idAsLong: Long? get() = id.toLongOrNull()
}

/** A resource's own links. */
@Serializable
data class KitsuResourceLinks(
    @SerialName("self") val self: String? = null,
    @SerialName("related") val related: String? = null,
)

/**
 * A relationship.
 *
 * [data] is nullable and **frequently absent**: Kitsu omits it unless the relationship was
 * included, so it must not be declared as `List<...>` non-null with a default. When present
 * it may still be empty, which means "no linked resources", not "not loaded".
 */
@Serializable
data class KitsuRelationship(
    @SerialName("links") val links: KitsuResourceLinks? = null,
    @SerialName("data") val data: List<KitsuResourceIdentifier>? = null,
)

/** A type-only reference, as it appears inside relationship data. */
@Serializable
data class KitsuResourceIdentifier(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
)

/** Document-level links. */
@Serializable
data class KitsuLinks(
    @SerialName("self") val self: String? = null,
    @SerialName("first") val first: String? = null,
    @SerialName("prev") val prev: String? = null,
    @SerialName("next") val next: String? = null,
    @SerialName("last") val last: String? = null,
)

/**
 * Pagination counters.
 *
 * [count] is the **total matching records**, not the page size — verified: a genre filter
 * returned `count: 22494` on a page holding one record. Kitsu does not report the current
 * page length here, so the page size comes from the request instead.
 */
@Serializable
data class KitsuMeta(
    @SerialName("count") val count: Int = 0,
)

/**
 * A JSON:API error object.
 *
 * Parsed so a 4xx can be reported with Kitsu's own message rather than a bare status code —
 * "Invalid filter" is actionable, "400" is not.
 */
@Serializable
data class KitsuError(
    @SerialName("id") val id: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("detail") val detail: String? = null,
)

/**
 * Holds a JSON:API `data` member that may be either a single object or an array.
 *
 * Exists so [KitsuClient] can decode both endpoint shapes through one path. The alternative —
 * a separate DTO per shape — duplicates every attribute model for a difference the spec itself
 * permits.
 */
sealed interface SingleOrMany<out T> {
    fun singleOrNull(): T?
    fun toList(): List<T>

    data class One<T>(val value: T) : SingleOrMany<T> {
        override fun singleOrNull(): T = value
        override fun toList(): List<T> = listOf(value)
    }

    data class Many<T>(val values: List<T>) : SingleOrMany<T> {
        override fun singleOrNull(): T? = values.firstOrNull()
        override fun toList(): List<T> = values
    }

    /** Kitsu returned neither an object nor an array (an empty body, or an error page). */
    data object Empty : SingleOrMany<Nothing> {
        override fun singleOrNull(): Nothing? = null
        override fun toList(): List<Nothing> = emptyList()
    }
}