package com.auris.app.ui.source

import com.auris.app.core.model.MediaType
import com.auris.app.source.AnimeSourceAdapter
import com.auris.app.source.ExtensionLoader
import com.auris.app.source.LoadedSource
import com.auris.app.source.MediaSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource

/** A source the user can browse, reduced to what the catalogue UI needs to show it. */
data class BrowsableSource(
    /** Stable across restarts; the route argument. */
    val key: String,
    val name: String,
    val language: String,
    val packageName: String,
    val mediaType: MediaType,
)

/**
 * Resolves a [BrowsableSource.key] back to a [MediaSource].
 *
 * ### Why a key rather than the source itself
 * A loaded source is a live object holding a `DexClassLoader` and an HTTP stack; it is neither
 * serialisable nor stable across process death. The key is the pair Aniyomi itself uses to
 * identify a source uniquely — package name plus the source's own id — so a route can survive a
 * configuration change and a process restart, and resolve back to whatever is loaded now.
 *
 * Nothing here loads an extension. That is [ExtensionLoader]'s job, driven by the install path;
 * this only maps onto what is already loaded, so a source the user has disabled or uninstalled
 * resolves to null and the screen shows "not available" rather than crashing.
 */
object SourceResolver {

    /**
     * Builds the key for a loaded source.
     *
     * The separator is `::` because both components can contain `/` and `.`, and an id is numeric
     * — so a key is unambiguous and never decodes to two different sources.
     */
    fun keyOf(loaded: LoadedSource): String =
        "${loaded.packageName}::${loaded.source.id}"

    /** Every currently-loaded source, in a stable order for the UI to list. */
    fun browsable(loader: ExtensionLoader): List<BrowsableSource> =
        loader.sources.map { loaded ->
            BrowsableSource(
                key = keyOf(loaded),
                name = loaded.sourceName,
                language = loaded.sourceLang,
                packageName = loaded.packageName,
                mediaType = mediaTypeFor(loaded),
            )
        }.sortedWith(compareBy({ it.mediaType.ordinal }, { it.name }))

    private fun mediaTypeFor(loaded: LoadedSource): MediaType {
        // A source does not declare whether it is anime or manga — that is a presentation choice
        // the user makes. Aniyomi's anime extensions are the ones this app can load at all, and
        // the API is shared, so default to anime and let the caller present manga when the user
        // is on the manga tab.
        return MediaType.ANIME
    }

    /**
     * Resolves [key] to a browsing [MediaSource], or null when it is not loaded.
     *
     * A null return is a normal state, not an error: the source may be disabled, uninstalled, or
     * the process may have restarted before the loader ran.
     */
    fun resolve(loader: ExtensionLoader, key: String): MediaSource? {
        val (packageName, id) = key.split("::", limit = 2).takeIf { it.size == 2 }
            ?: return null
        val sourceId = id.toLongOrNull() ?: return null

        val loaded = loader.sources.firstOrNull { candidate ->
            candidate.packageName == packageName && candidate.source.id == sourceId
        } ?: return null

        return AnimeSourceAdapter(
            aniyomiSource = loaded.source,
            packageName = loaded.packageName,
            mediaType = mediaTypeFor(loaded),
        )
    }

    /** The display name for [key], for the screen title before the first load completes. */
    fun nameOf(loader: ExtensionLoader, key: String): String? =
        loader.sources
            .firstOrNull { keyOf(it) == key }
            ?.sourceName
}

/** What the catalogue screen renders. */
data class CatalogueUiState(
    val sourceKey: String = "",
    val sourceName: String = "",
    val sourceLanguage: String = "",
    val isSourceAvailable: Boolean = true,
    val mode: Mode = Mode.POPULAR,
    val query: String = "",
    val items: List<com.auris.app.source.SourceItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasNextPage: Boolean = false,
) {
    enum class Mode { POPULAR, LATEST, SEARCH }

    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null
    val title: String
        get() = when {
            isSearching && query.isNotBlank() -> "\"$query\""
            else -> when (mode) {
                Mode.POPULAR -> "Popular"
                Mode.LATEST -> "Latest"
                Mode.SEARCH -> "Search"
            }
        }

    val isSearching: Boolean get() = mode == Mode.SEARCH && query.isNotBlank()
}
