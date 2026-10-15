package com.auris.app.ui.extensions

import com.auris.app.source.repo.ExtensionListItem

/**
 * Whether [this] item survives the NSFW toggle and the search query.
 *
 * Pure so the rule is unit-tested rather than verified by scrolling a 250-row
 * list on device: the ViewModel applies it inside its `combine`, and the screen
 * only renders what survives.
 */
internal fun ExtensionListItem.matchesListFilter(query: String, includeNsfw: Boolean): Boolean {
    if (!includeNsfw && remote.isNsfw) return false
    val needle = query.trim()
    if (needle.isBlank()) return true
    return remote.name.contains(needle, ignoreCase = true) ||
        remote.packageName.contains(needle, ignoreCase = true) ||
        remote.lang.contains(needle, ignoreCase = true)
}
