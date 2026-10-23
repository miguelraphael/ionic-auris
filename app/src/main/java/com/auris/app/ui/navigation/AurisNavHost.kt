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
import androidx.compose.runtime.LaunchedEffect
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
import com.auris.app.ui.library.LibraryViewModel
import com.auris.app.ui.player.MusicPlayerRoute
import android.app.Activity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.ui.diagnostics.DiagnosticsRoute
import com.auris.app.ui.player.EpisodePlayer
import com.auris.app.data.repository.ExtensionRepository
import com.auris.app.ui.settings.SettingsScreen
import com.auris.app.ui.source.CatalogueScreen
import com.auris.app.ui.source.CatalogueViewModel
import com.auris.app.ui.settings.SettingsViewModel
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.ui.music.MusicBrowseScreen
import com.auris.app.ui.music.MusicBrowseViewModel
import com.auris.app.ui.music.MusicHomeScreen
import com.auris.app.ui.music.MusicRow
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
import kotlinx.coroutines.flow.collectLatest

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
    /** Shared with the extensions screen: one repository, one signing key. */
    extensionRepoStore: com.auris.app.data.repository.ExtensionRepoStore,
    /** Loads sources so a catalogue route can resolve them. */
    extensionLoader: com.auris.app.source.ExtensionLoader,
    /** Owns the Settings account section; required, like the detail repositories. */
    authRepository: com.auris.app.data.repository.AuthRepository,
    extensionRepo: ExtensionRepo = ExtensionRepo.DEFAULT,
    /** Receives a resolved, playable stream. ExoPlayer hosting is the next slice. */
    /** Process-scoped so the player survives navigation between episodes. */
    episodePlayer: EpisodePlayer? = null,
    appStateCollector: com.auris.app.diagnostics.AppStateCollector? = null,
    /**
     * The music surface: catalogue and audio, behind one seam.
     *
     * Nullable so previews and the episode-only slices can omit it; the music route is guarded.
     */
    musicSource: MusicSource? = null,
    /**
     * The Invidious instance list, editable from Settings.
     *
     * Required rather than nullable on purpose: the audio source reads this list per track, so a
     * second store built anywhere else would silently disagree with the resolver about which
     * instances are configured.
     */
    invidiousInstanceStore: com.auris.app.data.repository.InvidiousInstanceStore,
    modifier: Modifier = Modifier,
) {
    // One ViewModel for both the Music home and the browse/search screen, hosted by the
    // Activity rather than either destination. Two instances would mean two controllers, two
    // copies of the queue, and a home screen that could not see what browse was playing.
    val musicViewModel: MusicBrowseViewModel? = if (musicSource != null) {
        viewModel(
            key = "music",
            factory = MusicBrowseViewModel.factory(
                source = musicSource,
                libraryRepository = libraryRepository,
                context = navController.context,
            ),
        )
    } else {
        null
    }

    // A saved music row is a playback instruction, not a destination. Anime and manga rows
    // open their screens; routing a music row to the player without starting it is how every
    // music card on Home ended up showing whatever was already playing — or "Nothing is
    // playing" on a cold start.
    val openLibraryEntry: (LibraryEntryEntity) -> Unit = { entry ->
        val music = musicViewModel
        if (entry.mediaType == MediaType.MUSIC && music != null) {
            music.play(entry.toMusicRow(), openPlayer = true)
        } else {
            onEntryClick(entry)
        }
    }

    // The player opens only once the queue is playing, so it never flashes its empty state
    // while a stream is still resolving.
    LaunchedEffect(musicViewModel) {
        musicViewModel?.playerOpenRequests?.collectLatest {
            navController.navigate(Routes.PLAYER)
        }
    }

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
                onEntryClick = openLibraryEntry,
                // The one entry point into Settings, and therefore into the extension
                // manager. Without it, Settings and Extensions only opened each other and
                // neither could be reached from any top-level destination.
                onOpenSettings = { navController.navigate(Routes.settings) },
                onSeeAll = { type ->
                    val destination = when (type) {
                        MediaType.ANIME -> TopLevelDestination.ANIME
                        MediaType.MANGA -> TopLevelDestination.MANGA
                        MediaType.MUSIC -> TopLevelDestination.MUSIC
                    }
                    navController.navigate(destination.route)
                },
            )
        }

        composable(TopLevelDestination.ANIME.route) {
            BrowseScreen(
                mediaType = MediaType.ANIME,
                entries = library.filter { it.mediaType == MediaType.ANIME },
                onEntryClick = openLibraryEntry,
            )
        }

        composable(TopLevelDestination.MANGA.route) {
            BrowseScreen(
                mediaType = MediaType.MANGA,
                entries = library.filter { it.mediaType == MediaType.MANGA },
                onEntryClick = openLibraryEntry,
            )
        }

        // The Music pillar is a shelf-based home rather than a saved-tracks grid: on first run
        // an empty library made the pillar look broken, and there was nothing to play from it.
        composable(TopLevelDestination.MUSIC.route) {
            val music = musicViewModel
            if (music == null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }
            val musicState by music.state.collectAsStateWithLifecycle()

            // Loaded when the pillar is opened, not at app start: five catalogue requests on
            // launch would compete with the library sync for nothing.
            LaunchedEffect(Unit) { music.loadHome() }

            MusicHomeScreen(
                state = musicState,
                savedMusic = library.filter { it.mediaType == MediaType.MUSIC },
                onPlay = music::play,
                onPlayEntry = { entry -> music.play(entry.toMusicRow()) },
                onOpenSearch = { navController.navigate(Routes.MUSIC_BROWSE) },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                onTogglePlayPause = music::togglePlayPause,
            )
        }

        composable(TopLevelDestination.LIBRARY.route) {
            // Scoped to the destination, not the Activity: the Library is the only screen that
            // deletes, so there is nothing to share.
            val libraryViewModel: LibraryViewModel = viewModel(
                key = "library",
                factory = LibraryViewModel.factory(libraryRepository),
            )
            LibraryScreen(
                entries = library,
                onEntryClick = openLibraryEntry,
                onDelete = libraryViewModel::delete,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Full-screen player. Not a top-level destination, so the nav pill is hidden
        // while it is open. It takes no argument: the live playback session is the state.
        composable(
            route = Routes.PLAYER,
            enterTransition = {
                slideInHorizontally(tween(280)) { it } + fadeIn(tween(280))
            },
            exitTransition = {
                slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240))
            },
            popExitTransition = {
                slideOutHorizontally(tween(240)) { it } + fadeOut(tween(240))
            },
        ) {
            MusicPlayerRoute(onBack = { navController.popBackStack() })
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
                // Tapping a source's name browses it; the toggle on the right still
                // enables/disables. Without this there is no path from installing an extension
                // to ever seeing its content.
                onOpenCatalogue = { sourceKey ->
                    sourceKey.takeIf { it.isNotBlank() }
                        ?.let { navController.navigate(Routes.catalogue(it)) }
                },
                // No nav pill on this screen, so the header carries its own back.
                onBack = { navController.popBackStack() },
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

        // Music browse/search. Reached from the Music home's search field.
        composable(Routes.MUSIC_BROWSE) {
            val music = musicViewModel
            if (music == null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }
            val musicState by music.state.collectAsStateWithLifecycle()

            MusicBrowseScreen(
                state = musicState,
                onQueryChange = music::search,
                onPlay = music::play,
                onSave = music::saveToLibrary,
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
            )
        }

        // Settings. Diagnostics moved under it: a diagnostics screen reached from an
        // extensions button was a placeholder for a settings screen that did not exist yet.
        composable(Routes.SETTINGS) {
            val settingsViewModel: SettingsViewModel = viewModel(
                key = "settings",
                factory = SettingsViewModel.factory(
                    extensionRepository = extensionRepository,
                    repoStore = extensionRepoStore,
                    authRepository = authRepository,
                    invidiousInstanceStore = invidiousInstanceStore,
                ),
            )
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()

            SettingsScreen(
                state = settingsState,
                onRepoUrlChange = settingsViewModel::setRepoUrl,
                onBeginRepoEdit = settingsViewModel::cancelRepoEdit,
                onCancelRepoEdit = settingsViewModel::cancelRepoEdit,
                onFingerprintChange = settingsViewModel::setSigningKeyFingerprint,
                onResetRepo = settingsViewModel::resetRepo,
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                onOpenDiagnostics = { navController.navigate(Routes.diagnostics) },
                onSignOut = settingsViewModel::signOut,
                onDeleteAccount = settingsViewModel::deleteAccount,
                onInvidiousDraftChange = settingsViewModel::setInvidiousDraft,
                onAddInvidiousInstance = settingsViewModel::addInvidiousInstance,
                onRemoveInvidiousInstance = settingsViewModel::removeInvidiousInstance,
                onResetInvidiousInstances = settingsViewModel::resetInvidiousInstances,
            )
        }

        // A source's catalogue. The bridge between an installed extension and any content the
        // user can see; before this existed an extension could be installed but never browsed.
        composable(
            route = Routes.CATALOGUE,
            arguments = listOf(
                navArgument("sourceKey") { type = NavType.StringType },
            ),
        ) { entry ->
            val sourceKey = entry.arguments?.getString("sourceKey").orEmpty()

            val catalogueViewModel: CatalogueViewModel = viewModel(
                key = "catalogue-$sourceKey",
                factory = CatalogueViewModel.factory(
                    extensionLoader = extensionLoader,
                    sourceKey = sourceKey,
                    extensionContentRepository = extensionContentRepository,
                    libraryRepository = libraryRepository,
                ),
            )
            val catalogueState by catalogueViewModel.state.collectAsStateWithLifecycle()

            // The ViewModel owns the upsert and emits the persisted id; navigation
            // itself stays here in the UI layer — no NavController in the ViewModel.
            LaunchedEffect(catalogueViewModel) {
                catalogueViewModel.openDetail.collectLatest { entryId ->
                    navController.navigate(Routes.animeDetail(entryId))
                }
            }

            CatalogueScreen(
                state = catalogueState,
                onItemClick = catalogueViewModel::onItemClick,
                onModeChange = catalogueViewModel::selectMode,
                onQueryChange = catalogueViewModel::onQueryChange,
                onRetry = catalogueViewModel::retry,
                modifier = Modifier,
                onBack = { navController.popBackStack() },
            )
        }

        // Diagnostics / log export.
        composable(Routes.DIAGNOSTICS) {
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
                onBack = { navController.popBackStack() },
            )
        }
    }
}


/**
 * Rebuilds the playback identity of a saved music row.
 *
 * A saved row stores the YouTube video id in `sourceId`; `sourceKey` is the provider name
 * ("innertube") and would resolve to nothing.
 */
private fun LibraryEntryEntity.toMusicRow() = MusicRow(
    kind = MusicRow.Kind.TRACK,
    title = title,
    subtitle = author ?: subtitle,
    artworkUrl = artworkUrl ?: localArtworkPath,
    id = sourceId,
)
