package com.auris.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.ui.graphics.vector.ImageVector
import com.auris.app.R

/**
 * Top-level destinations. The floating pill shows exactly these five.
 *
 * Routes are declared as constants rather than a sealed hierarchy because detail routes
 * (player, reader, episode list) are added incrementally and should not force edits here.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home),
    ANIME("anime", R.string.nav_anime, Icons.Filled.SmartDisplay),
    MANGA("manga", R.string.nav_manga, Icons.AutoMirrored.Filled.MenuBook),
    MUSIC("music", R.string.nav_music, Icons.Filled.LibraryMusic),
    LIBRARY("library", R.string.nav_library, Icons.Filled.GridView),
}

/** Detail and full-screen routes, addressed by helper builders to avoid string typos. */
object Routes {
    const val PLAYER = "player/{entryId}"
    const val ANIME_DETAIL = "anime/{entryId}"
    const val MANGA_READER = "manga/reader/{entryId}/{unitIndex}"
    const val EXTENSIONS = "settings/extensions"
    const val SEARCH = "search"
    const val EPISODE_PLAYER = "play/episode/{entryId}/{unitIndex}"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val MUSIC_BROWSE = "music/browse"
    const val SETTINGS = "settings"

    fun player(entryId: String) = "player/$entryId"
    fun animeDetail(entryId: String) = "anime/$entryId"
    fun mangaReader(entryId: String, unitIndex: Int) = "manga/reader/$entryId/$unitIndex"
    fun episodePlayer(entryId: String, unitIndex: Int) = "play/episode/$entryId/$unitIndex"
    const val diagnostics = DIAGNOSTICS
    const val settings = SETTINGS
}