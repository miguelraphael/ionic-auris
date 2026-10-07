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
import androidx.compose.runtime.DisposableEffect
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
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.ui.diagnostics.DiagnosticsRoute
import com.auris.app.ui.player.EpisodePlayer
import com.auris.app.data.repository.ExtensionRepository
import com.auris.app.ui.settings.SettingsScreen
import com.auris.app.ui.settings.SettingsViewModel
import com.auris.app.data.remote.music.InnerTubeClient
import com.auris.app.ui.music.MusicBrowseScreen
import com.auris.app.ui.music.MusicLibraryEntry
import com.auris.app.ui.music.MusicBrowseViewModel
import com.auris.app.ui.player.EpisodePlayerRoute
import com.auris.app.ui.reader.MangaReaderScreen
import com.auris.app.ui.reader.MangaReaderViewModel
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
    /** Read by Settings to report the trust state of installed extensions. */
    extensionRepository: ExtensionRepository,
    extensionRepo: ExtensionRepo = ExtensionRepo.DEFAULT,
    /** Receives a resolved, playable stream. ExoPlayer hosting is the next slice. */
    /** Process-scoped so the player survives navigation between episodes. */
    episodePlayer: EpisodePlayer? = null,
    appStateCollector: com.auris.app.diagnostics.AppStateCollector? = null,
    innerTubeClient: InnerTubeClient? = null,
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

        // The Music pillar leads to the live YouTube Music browser rather than a static grid:
        // with no library yet, the saved-tracks grid would be empty on first run and the app
        // would look broken. The library is still reachable from the browse screen.
        composable(TopLevelDestination.MUSIC.route) {
            MusicLibraryEntry(
                entries = library.filter { it.mediaType == MediaType.MUSIC },
                onEntryClick = onEntryClick,
                onOpenBrowse = { navController.navigate(Routes.MUSIC_BROWSE) },
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
                // Was a no-op: the Diagnostics button existed but had no destination before
                // Settings existed. Now wired, with Diagnostics reachable from inside Settings.
                onOpenSettings = { navController.navigate(Routes.settings) },
                bottomPadding = 32.dp + WindowInsets.navigationBars
                    .asPaddingValues()
                    .calculateBottomPadding(),
            )
        }

        // Manga reader. Resolves page images on open; see MangaReaderViewModel for why they
        // are never cached.
        composable(
            route = Routes.MANGA_READER,
            arguments = listOf(
                navArgument("entryId") { type = NavType.StringType },
                navArgument("unitIndex") { type = NavType.StringType },
            ),
        ) { navEntry ->
            val entryId = navEntry.arguments?.getString("entryId").orEmpty()
            val unitIndex = navEntry.arguments?.getString("unitIndex")?.toIntOrNull() ?: 0

            val viewModel: MangaReaderViewModel = viewModel(
                key = "reader-$entryId-$unitIndex",
                factory = MangaReaderViewModel.factory(
                    entryId = entryId,
                    unitIndex = unitIndex,
                    libraryRepository = libraryRepository,
                    contentRepository = extensionContentRepository,
                ),
            )
            val readerState by viewModel.state.collectAsStateWithLifecycle()

            // System bars are hidden for the reader: the pages are the content, and a status bar
            // over artwork wastes vertical space on a phone.
            val window = (LocalView.current.context as? Activity)?.window
            DisposableEffect(Unit) {
                val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
                controller?.hide(WindowInsetsCompat.Type.systemBars())
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
            }

            MangaReaderScreen(
                state = readerState,
                onPageChanged = viewModel::onPageChanged,
                onToggleOverlay = viewModel::toggleOverlay,
                onRetry = viewModel::retry,
                onBack = { navController.popBackStack() },
            )
        }

        // Episode player. Reached from the detail screen with a stream already resolved.
        composable(
            route = Routes.EPISODE_PLAYER,
            arguments = listOf(
                navArgument("entryId") { type = NavType.StringType },
                navArgument("unitIndex") { type = NavType.StringType },
            ),
        ) { navEntry ->
            val entryId = navEntry.arguments?.getString("entryId").orEmpty()
            val unitIndex = navEntry.arguments?.getString("unitIndex")?.toIntOrNull() ?: 0
            val entry = library.firstOrNull { it.id == entryId }
            if (entry == null || episodePlayer == null) {
                // A deep link to an entry that is not in the snapshot, or a preview without a
                // player. Falling back to the background beats a blank route.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }

            EpisodePlayerRoute(
                entry = entry,
                // The unit's source URL is not in the library snapshot, so it is reconstructed
                // from the entry's units by the caller's screen; here the index is enough to
                // identify the episode and resolve its stream.
                unit = com.auris.app.source.SourceUnit(
                    url = "",
                    index = unitIndex,
                    title = "Episode ${unitIndex + 1}",
                ),
                contentRepository = extensionContentRepository,
                episodePlayer = episodePlayer,
                onBack = { navController.popBackStack() },
            )
        }

        // Music browse/search. Reached from the Music pillar on the nav pill.
        composable(Routes.MUSIC_BROWSE) {
            if (innerTubeClient == null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }
            val viewModel: MusicBrowseViewModel = viewModel(
                key = "music-browse",
                factory = MusicBrowseViewModel.factory(
                    client = innerTubeClient,
                    libraryRepository = libraryRepository,
                    context = navController.context,
                ),
            )
            val musicState by viewModel.state.collectAsStateWithLifecycle()

            MusicBrowseScreen(
                state = musicState,
                onQueryChange = viewModel::search,
                onPlay = viewModel::play,
                onSave = viewModel::saveToLibrary,
                onOpenPlayer = {
                    // Only meaningful with something playing; the route is cheap and shows the
                    // existing player state if not.
                    navController.navigate(Routes.PLAYER)
                },
            )
        }

        // Settings. Diagnostics moved under it: a diagnostics screen reached from an
        // extensions button was a placeholder for a settings screen that did not exist yet.
        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                key = "settings",
                factory = SettingsViewModel.factory(extensionRepository = extensionRepository),
            )
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()

            SettingsScreen(
                state = settingsState,
                onRepoUrlChange = settingsViewModel::setRepoUrl,
                onBeginRepoEdit = settingsViewModel::cancelRepoEdit,
                onCancelRepoEdit = settingsViewModel::cancelRepoEdit,
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                onOpenDiagnostics = { navController.navigate(Routes.diagnostics) },
            )
        }

        // Diagnostics / log export.
        composable(Routes.DIAGNOSTICS) {
            if (innerTubeClient == null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }
            DiagnosticsRoute(
                onBack = { navController.popBackStack() },
                appStateCollector = appStateCollector,
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
                        onPlay = { unit, _ ->
                            // The stream was already resolved by the view model; the player
                            // resolves it again on arrival so it owns the player/stream pairing
                            // and the headers travel with it.
                            navController.navigate(Routes.episodePlayer(entryId, unit.index))
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