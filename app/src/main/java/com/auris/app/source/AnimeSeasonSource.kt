package com.auris.app.source

/** Optional built-in capability; independent of the extension binary API. */
interface AnimeSeasonSource {
    suspend fun seasons(entry: SourceItem): List<SourceSeason>
}

data class SourceSeason(val item: SourceItem, val label: String, val isCurrent: Boolean)
