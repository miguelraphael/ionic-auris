package eu.kanade.tachiyomi.animesource.model

/**
 * A playable audio/video track on a host page, as opposed to a [Video] which is a resolved stream.
 *
 * ### Why this is a real class and not a `typealias`
 * Found by loading a real v16 Aniyomi extension, whose `getVideoList` path references
 * `animesource/model/Track` and constructs it as `(String, String)`. A `typealias` would emit no
 * JVM class and the extension would fail to link — the same failure `AnimeFilterList` had.
 *
 * The two constructor arguments are `[url, name]`, read from the extension's dex.
 */
data class Track(
    /** The track's own URL on the host page — the thing that has to be resolved into a stream. */
    val url: String,

    /** Human-readable track name, e.g. "1080p" or a subtitle track's language. */
    val name: String = "",
)
