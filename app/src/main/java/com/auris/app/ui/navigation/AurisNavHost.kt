package com.auris.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.browse.BrowseScreen
import com.auris.app.ui.home.HomeScreen
import com.auris.app.ui.library.LibraryScreen
import com.auris.app.ui.player.MusicPlayerScreen
import com.auris.app.ui.player.PlayerUiState
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.KitsuIdentifier
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.ui.detail.MediaDetailScreen
import com.auris.app.ui.detail.MediaDetailViewModel
import com.auris.app.source.SourceUnit
import com.auris.app.source.StreamTarget
import com.auris.app.ui.extensions.ExtensionsScreen
import com.auris.app.ui.extensions.ExtensionsViewModel
import com.auris.app.ui.extensions.actions
import com.auris.app.source.repo.ExtensionRepo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The single NavHost for the app.
 *
 * Only screens with real implementations are wired up; the anime/manga detail routes are
 * declared with a placeholder so deep links and back-stack behaviour can be exercised
 * before those features land. Everything else is added as it is implemented rather than
 * stubbed out up front.
 */
@Composable
fun AurisNavHost(
    navController: NavHostController,
    library: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    // Required, not nullable: the detail screen cannot function without them, and making them
    // optional would mean a silently empty episode list rather than a compile error at the call
    // site. `extensionsViewModelFactory` stays optional because previews do not need it.
    libraryRepository: LibraryRepository,
    kitsuIdentifier: KitsuIdentifier,
    extensionContentRepository: ExtensionContentRepository,
    extensionsViewModelFactory: ViewModelProvider.Factory? = null,
    extensionRepo: ExtensionRepo = ExtensionRepo.DEFAULT,
    /** Receives a resolved, playable stream. ExoPlayer hosting is the next slice. */
    playEpisode: (entryId: String, unit: SourceUnit, targets: List<StreamTarget>) -> Unit =
        { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.HOME.route,
        modifier = modifier.fillMaxSize(),
        // Matches the pill: content slides in rather than a full-width block swapping.
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) },
    ) {
        composable(TopLevelDestination.HOME.route) {
            HomeScreen(
                library = library,
                onEntryClick = onEntryClick,
            )
        }

        composable(TopLevelDestination.ANIME.route) {
            BrowseScreen(
                mediaType = MediaType.ANIME,
                entries = library.filter { it.mediaType == MediaType.ANIME },
                onEntryClick = onEntryClick,
            )
        }

        composable(TopLevelDestination.MANGA.route) {
            BrowseScreen(
                mediaType = MediaType.MANGA,
                entries = library.filter { it.mediaType == MediaType.MANGA },
                onEntryClick = onEntryClick,
            )
        }

        composable(TopLevelDestination.MUSIC.route) {
            BrowseScreen(
                mediaType = MediaType.MUSIC,
                entries = library.filter { it.mediaType == MediaType.MUSIC },
                onEntryClick = onEntryClick,
            )
        }

        composable(TopLevelDestination.LIBRARY.route) {
            LibraryScreen(
                entries = library,
                onEntryClick = onEntryClick,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Full-screen player. Not a top-level destination, so the nav pill is hidden
        // while it is open.
        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
            enterTransition = {
                slideInHorizontally(tween(280)) { it } + fadeIn(tween(280))
            },
            exitTransition = {
                slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240))
            },
            popExitTransition = {
                slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240))
            },
        ) { entry ->
            val entryId = entry.arguments?.getString("entryId").orEmpty()
            val track = library.firstOrNull { it.id == entryId }
            MusicPlayerScreen(
                state = track?.toPlayerState() ?: PlayerUiState(),
                onPlayPause = { /* bound by PlayerViewModel in the next slice */ },
                onSeekFraction = {},
                onVolumeChange = {},
                onSkipBack = {},
                onSkipForward = {},
                onCycleRepeat = {},
                onToggleShuffle = {},
            )
        }

        // Extension management. Reached from Settings, not a top-level tab, so the nav pill
        // is hidden here and the screen reserves its own bottom inset instead.
        composable(Routes.EXTENSIONS) { entry ->
            if (extensionsViewModelFactory == null) return@composable

            val viewModel: ExtensionsViewModel = viewModel(
                factory = extensionsViewModelFactory,
                key = "extensions",
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            ExtensionsScreen(
                state = state,
                actions = viewModel.actions(),
                repo = extensionRepo,
                bottomPadding = 32.dp + WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding(),
            )
        }

        composable(
            route = Routes.ANIME_DETAIL,
            arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
        ) { navEntry ->
            val entryId = navEntry.arguments?.getString("entryId").orEmpty()
            val entry = library.firstOrNull { it.id == entryId }

            if (entry == null) {
                // A deep link or a stale back-stack entry can name a row that no longer exists;
                // rendering the shell's background is better than a blank white screen.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }

            // Keyed by entry id so navigating between two titles does not reuse the first
            // one's state, and so a stale view model is not retained for a back-stack entry.
            val viewModel: MediaDetailViewModel = viewModel(
                key = "detail-$entryId",
                factory = MediaDetailViewModel.factory(
                    entryId = entryId,
                    libraryRepository = libraryRepository,
                    identifier = kitsuIdentifier,
                    contentRepository = extensionContentRepository,
                ),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            MediaDetailScreen(
                state = state,
                onUnitClick = { unit ->
                    viewModel.onUnitClick(
                        unit = unit,
                        onNavigateToUnit = { index ->
                            navController.navigate(Routes.mangaReader(entryId, index))
                        },
                        // Anime playback needs a resolved stream and an ExoPlayer host, which is
                        // the next slice. The stream *is* resolved here, so the caller receives a
                        // playable URL rather than an embed page.
                        onPlay = { unit, targets ->
                            playEpisode(entryId, unit, targets)
                        },
                    )
                },
                onRetry = viewModel::retry,
                bottomPadding = 32.dp + WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding(),
            )
        }
    }
}

/** Maps a library row onto the player's view state. */
internal fun LibraryEntryEntity.toPlayerState(): PlayerUiState = PlayerUiState(
    entryId = id,
    title = title,
    artist = author.orEmpty(),
    album = subtitle.orEmpty(),
    artworkUrl = artworkUrl ?: localArtworkPath,
    isPlaying = false,
    durationMs = totalUnits.toLong() * 180_000L,
    positionMs = lastPositionMs,
)