package eu.kanade.tachiyomi.source.model

/**
 * How often a source should be re-checked for updates.
 *
 * Anikku keeps both this and `AnimeUpdateStrategy`: `SAnime.update_strategy` is typed
 * `AnimeUpdateStrategy`, while its `copy()` overload takes an `UpdateStrategy`. Both exist
 * upstream, so both exist here.
 */
enum class UpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}
