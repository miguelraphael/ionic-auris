package com.auris.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.auris.app.ui.theme.AurisMotion
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.auris.app.ui.music.MusicRowSaver
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.auris.app.ui.source.PreviewDetail
import com.auris.app.ui.settings.SettingsViewModel
import com.auris.app.data.remote.music.MusicSource
import com.auris.app.data.remote.music.MusicArtistDetails
import com.auris.app.ui.music.MusicArtistPage
import com.auris.app.ui.music.MusicBrowseScreen
import com.auris.app.ui.music.MusicBrowseViewModel
import com.auris.app.ui.music.MusicHomeScreen
import com.auris.app.ui.music.MusicRow
import com.auris.app.ui.music.artistHint
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
import kotlinx.coroutines.launch

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
    /** True until the first Room emission arrives; distinguishes loading from empty. */
    isLibraryLoading: Boolean = false,
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
    appUpdateChecker: com.auris.app.data.remote.updater.AppUpdateChecker? = null,
    /**
     * The music surface: catalogue and audio, behind one seam.
     *
     * Nullable so previews and the episode-only slices can omit it; the music route is guarded.
     */
    musicSource: MusicSource? = null,
    modifier: Modifier = Modifier,
) {
    val activeEntry = navController.currentBackStackEntryAsState()
    val sourcePreferences = remember {
        navController.context.applicationContext.getSharedPreferences("catalogue_sources", android.content.Context.MODE_PRIVATE)
    }
    fun selectedSource(type: MediaType, default: String): String =
        sourcePreferences.getString(type.name, default)?.takeIf { key ->
            extensionContentRepository.builtInSources.any {
                it.mediaType == type && ("builtin-${it.id}" == key || it.id == key)
            }
        } ?: default

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

    // Unsaved catalogue previews, keyed by a per-tap id carried in the preview route.
    // A map (not a single slot) keeps each back-stack entry on its own title.
    val previewItems = remember { mutableStateMapOf<String, PreviewDetail>() }

    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.HOME.route,
        modifier = modifier.fillMaxSize(),
        enterTransition = {
            val isTab = TopLevelDestination.entries.any { it.route == targetState.destination.route }
            val isVideo = targetState.destination.route == Routes.EPISODE_PLAYER
            if (isTab || isVideo) {
                fadeIn(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            } else {
                slideInHorizontally(AurisMotion.SlideSpring) { it } +
                    fadeIn(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            }
        },
        exitTransition = {
            val isTab = TopLevelDestination.entries.any { it.route == targetState.destination.route }
            val isVideo = targetState.destination.route == Routes.EPISODE_PLAYER
            if (isTab || isVideo) {
                fadeOut(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            } else {
                slideOutHorizontally(AurisMotion.SlideSpring) { -it / 3 } +
                    fadeOut(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            }
        },
        popEnterTransition = {
            val isTab = TopLevelDestination.entries.any { it.route == targetState.destination.route }
            val isVideo = targetState.destination.route == Routes.EPISODE_PLAYER
            if (isTab || isVideo) {
                fadeIn(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            } else {
                slideInHorizontally(AurisMotion.SlideSpring) { -it / 3 } +
                    fadeIn(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            }
        },
        popExitTransition = {
            val isTab = TopLevelDestination.entries.any { it.route == initialState.destination.route }
            val isVideo = initialState.destination.route == Routes.EPISODE_PLAYER
            if (isTab || isVideo) {
                fadeOut(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            } else {
                slideOutHorizontally(AurisMotion.SlideSpring) { it } +
                    fadeOut(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            }
        },
    ) {
        composable(TopLevelDestination.HOME.route) {
            val musicState by musicViewModel?.state?.collectAsStateWithLifecycle()
                ?: remember { mutableStateOf(com.auris.app.ui.music.MusicBrowseUiState()) }
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
                isLoading = isLibraryLoading,
                nowPlayingId = musicState.nowPlayingId,
                nowPlayingTitle = musicState.nowPlayingTitle,
                nowPlayingArtist = musicState.nowPlayingArtist,
                nowPlayingArtworkUrl = musicState.nowPlayingArtworkUrl,
                isPlaying = musicState.isPlaying,
                isResolving = musicState.isResolving,
                isBuffering = musicState.isBuffering,
                canGoPrevious = musicState.canGoPrevious,
                canGoNext = musicState.canGoNext,
                onOpenPlayer = { if (!musicState.isResolving) navController.navigate(Routes.PLAYER) },
                onTogglePlayPause = { musicViewModel?.togglePlayPause() },
                onPrevious = { musicViewModel?.previous() },
                onNext = { musicViewModel?.next() },
            )
        }

        composable(TopLevelDestination.ANIME.route) {
            val animeCatalogue: CatalogueViewModel = viewModel(
                key = "anime-catalogue",
                factory = CatalogueViewModel.factory(
                    extensionLoader = extensionLoader,
                    sourceKey = selectedSource(MediaType.ANIME, com.auris.app.data.remote.anime.AllAnimeSource.KEY),
                    mediaType = MediaType.ANIME,
                    extensionContentRepository = extensionContentRepository,
                ),
            )
            val animeState by animeCatalogue.state.collectAsStateWithLifecycle()
            LaunchedEffect(animeCatalogue) {
                animeCatalogue.openDetail.collectLatest { preview ->
                    val previewId = java.util.UUID.randomUUID().toString()
                    previewItems[previewId] = preview
                    navController.navigate(Routes.animePreview(previewId))
                }
            }
            CatalogueScreen(
                state = animeState,
                onItemClick = animeCatalogue::onItemClick,
                onModeChange = animeCatalogue::selectMode,
                onQueryChange = animeCatalogue::onQueryChange,
                onRetry = animeCatalogue::retry,
                onSourceChange = { key ->
                    animeCatalogue.selectSource(key)
                    sourcePreferences.edit().putString(MediaType.ANIME.name, key).apply()
                },
                onLoadMore = animeCatalogue::loadMore,
            )
        }

        composable(TopLevelDestination.MANGA.route) {
            val mangaCatalogue: CatalogueViewModel = viewModel(
                key = "manga-catalogue",
                factory = CatalogueViewModel.factory(
                    extensionLoader = extensionLoader,
                    sourceKey = selectedSource(MediaType.MANGA, com.auris.app.data.remote.manga.WeebCentralSource.KEY),
                    mediaType = MediaType.MANGA,
                    extensionContentRepository = extensionContentRepository,
                ),
            )
            val mangaState by mangaCatalogue.state.collectAsStateWithLifecycle()
            LaunchedEffect(mangaCatalogue) {
                mangaCatalogue.openDetail.collectLatest { preview ->
                    val previewId = java.util.UUID.randomUUID().toString()
                    previewItems[previewId] = preview
                    navController.navigate(Routes.animePreview(previewId))
                }
            }
            CatalogueScreen(
                state = mangaState,
                onItemClick = mangaCatalogue::onItemClick,
                onModeChange = mangaCatalogue::selectMode,
                onQueryChange = mangaCatalogue::onQueryChange,
                onRetry = mangaCatalogue::retry,
                onSourceChange = { key ->
                    mangaCatalogue.selectSource(key)
                    sourcePreferences.edit().putString(MediaType.MANGA.name, key).apply()
                },
                onLoadMore = mangaCatalogue::loadMore,
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

            var homeSongOptions by remember { mutableStateOf<MusicRow?>(null) }
            homeSongOptions?.let { song -> com.auris.app.ui.music.MusicSongActions(song, music) { homeSongOptions = null } }

            var selectedAlbum by rememberSaveable(stateSaver = MusicRowSaver) { mutableStateOf<MusicRow?>(null) }
            var albumTracks by remember { mutableStateOf(emptyList<MusicRow>()) }
            var albumLoading by remember { mutableStateOf(false) }
            var albumError by remember { mutableStateOf<String?>(null) }
            val albumScope = rememberCoroutineScope()
            fun playAlbum(index: Int = 0) {
                val album = selectedAlbum
                val albumArtist = album?.subtitle
                albumScope.launch {
                    try {
                        val tracksToPlay = albumTracks.map { track ->
                            if (track.artistHint().isNullOrBlank() && !albumArtist.isNullOrBlank()) {
                                track.copy(subtitle = albumArtist)
                            } else track
                        }
                        music.playPlaylist(tracksToPlay, index)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        albumError = "Could not start playback. Try another song."
                    }
                }
            }
            androidx.compose.runtime.LaunchedEffect(selectedAlbum) {
                val album = selectedAlbum ?: return@LaunchedEffect
                albumTracks = emptyList()
                albumError = null
                albumLoading = true
                try {
                    albumTracks = music.collectionRows(album.id).filter { it.kind == MusicRow.Kind.TRACK }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    albumError = "Could not load this album. Close it and try again."
                } finally {
                    albumLoading = false
                }
            }

            var selectedArtist by rememberSaveable(stateSaver = MusicRowSaver) { mutableStateOf<MusicRow?>(null) }
            var artistDetails by remember { mutableStateOf<com.auris.app.data.remote.music.MusicArtistDetails?>(null) }
            var artistRows by remember { mutableStateOf<List<MusicRow>>(emptyList()) }
            var isArtistLoading by remember { mutableStateOf(false) }
            val artistScope = rememberCoroutineScope()

            androidx.compose.runtime.LaunchedEffect(selectedArtist) {
                val artist = selectedArtist ?: return@LaunchedEffect
                artistDetails = null
                artistRows = emptyList()
                isArtistLoading = true
                try {
                    val (details, rows) = music.loadArtist(artist)
                    artistDetails = details
                    artistRows = rows
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // best-effort
                } finally {
                    isArtistLoading = false
                }
            }

            MusicHomeScreen(
                state = musicState,
                savedMusic = library.filter { it.mediaType == MediaType.MUSIC },
                onPlay = music::play,
                onPlayEntry = { entry -> music.play(entry.toMusicRow()) },
                onOpenSearch = { navController.navigate(Routes.MUSIC_BROWSE) },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
                onTogglePlayPause = music::togglePlayPause,
                onPrevious = music::previous,
                onNext = music::next,
                onRetryHome = music::retryHome,
                onOptions = { homeSongOptions = it },
                onOpenAlbum = { selectedAlbum = it },
                onOpenArtist = { selectedArtist = it },
            )
            selectedAlbum?.takeIf { activeEntry.value?.destination?.route == TopLevelDestination.MUSIC.route }?.let { album ->
                com.auris.app.ui.music.MusicAlbumPage(
                    title = album.title, tracks = albumTracks, loading = albumLoading, message = albumError ?: musicState.message,
                    onBack = { selectedAlbum = null },
                    onPlayAll = { playAlbum() },
                    onPlay = { index -> playAlbum(index) },
                    onOptions = { homeSongOptions = it },
                    isPlaylist = album.kind == MusicRow.Kind.PLAYLIST,
                    albumArtist = album.subtitle,
                    artworkUrl = album.artworkUrl,
                    bottomBar = {
                        if (musicState.nowPlayingId != null) com.auris.app.ui.music.NowPlayingStrip(
                            title = musicState.nowPlayingTitle ?: "Now playing", artist = musicState.nowPlayingArtist,
                            artworkUrl = musicState.nowPlayingArtworkUrl, isPlaying = musicState.isPlaying,
                            isLoading = musicState.isResolving || musicState.isBuffering,
                            onClick = { if (!musicState.isResolving) { navController.navigate(Routes.PLAYER) { launchSingleTop = true } } },
                            onTogglePlayPause = music::togglePlayPause, onPrevious = music::previous, onNext = music::next,
                            canGoPrevious = !musicState.isResolving && musicState.canGoPrevious,
                            canGoNext = !musicState.isResolving && musicState.canGoNext,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    },
                )
            }
            selectedArtist?.takeIf { activeEntry.value?.destination?.route == TopLevelDestination.MUSIC.route && selectedAlbum == null }?.let { artistRow ->
                MusicArtistPage(
                    name = artistDetails?.name ?: artistRow.artistHint().orEmpty().ifBlank { artistRow.title },
                    artwork = artistDetails?.photoUrl ?: artistRows.firstOrNull { it.artworkUrl != null }?.artworkUrl ?: artistRow.artworkUrl,
                    bio = artistDetails?.bio,
                    rows = artistRows.distinctBy { it.kind to it.id },
                    loading = isArtistLoading,
                    message = null,
                    onBack = { selectedArtist = null },
                    onPlay = { item -> music.play(item) },
                    onPlayAll = {
                        val tracks = artistRows.filter { it.kind == MusicRow.Kind.TRACK }
                        if (tracks.isNotEmpty()) {
                            artistScope.launch { music.playPlaylist(tracks) }
                        }
                    },
                    onCollection = { item -> selectedAlbum = item },
                    onOptions = { homeSongOptions = it },
                    bottomBar = {
                        if (musicState.nowPlayingId != null) com.auris.app.ui.music.NowPlayingStrip(
                            title = musicState.nowPlayingTitle ?: "Now playing", artist = musicState.nowPlayingArtist,
                            artworkUrl = musicState.nowPlayingArtworkUrl, isPlaying = musicState.isPlaying,
                            isLoading = musicState.isResolving || musicState.isBuffering,
                            onClick = { if (!musicState.isResolving) { navController.navigate(Routes.PLAYER) { launchSingleTop = true } } },
                            onTogglePlayPause = music::togglePlayPause, onPrevious = music::previous, onNext = music::next,
                            canGoPrevious = !musicState.isResolving && musicState.canGoPrevious,
                            canGoNext = !musicState.isResolving && musicState.canGoNext,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    },
                )
            }
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
                isLoading = isLibraryLoading,
            )
        }

        // Full-screen player. Not a top-level destination, so the nav pill is hidden
        // while it is open. It takes no argument: the live playback session is the state.
        composable(
            route = Routes.PLAYER,
            enterTransition = {
                slideInVertically(AurisMotion.SlideSpring) { it } +
                    fadeIn(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            },
            exitTransition = {
                slideOutVertically(AurisMotion.SlideSpring) { it } +
                    fadeOut(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            },
            popExitTransition = {
                slideOutVertically(AurisMotion.SlideSpring) { it } +
                    fadeOut(tween(AurisMotion.Standard, easing = AurisMotion.Ease))
            },
        ) {
            val music = musicViewModel
            val scope = rememberCoroutineScope()
            var artistDialogRow by remember { mutableStateOf<MusicRow?>(null) }
            var artistDialogDetails by remember { mutableStateOf<MusicArtistDetails?>(null) }
            var artistDialogRows by remember { mutableStateOf<List<MusicRow>>(emptyList()) }
            var isArtistLoading by remember { mutableStateOf(false) }

            MusicPlayerRoute(
                onBack = { navController.popBackStack() },
                onArtistClick = { artistName, trackId ->
                    if (music != null && artistName.isNotBlank()) {
                        val effectiveTrackId = trackId ?: music.state.value.nowPlayingId
                        val row = MusicRow(
                            id = effectiveTrackId ?: "artist-$artistName",
                            title = artistName,
                            subtitle = artistName,
                            artworkUrl = null,
                            kind = MusicRow.Kind.TRACK,
                        )
                        artistDialogRow = row
                        artistDialogDetails = null
                        artistDialogRows = emptyList()
                        isArtistLoading = true
                        scope.launch {
                            try {
                                val (details, rows) = music.loadArtist(row)
                                artistDialogDetails = details
                                artistDialogRows = rows
                            } finally {
                                isArtistLoading = false
                            }
                        }
                    }
                },
            )

            artistDialogRow?.let { row ->
                MusicArtistPage(
                    name = artistDialogDetails?.name ?: row.artistHint().orEmpty().ifBlank { row.title },
                    artwork = artistDialogDetails?.photoUrl ?: artistDialogRows.firstOrNull { it.artworkUrl != null }?.artworkUrl ?: row.artworkUrl,
                    bio = artistDialogDetails?.bio,
                    rows = artistDialogRows.distinctBy { it.kind to it.id },
                    loading = isArtistLoading,
                    message = null,
                    onBack = { artistDialogRow = null },
                    onPlay = { item -> music?.play(item) },
                    onPlayAll = {
                        val tracks = artistDialogRows.filter { it.kind == MusicRow.Kind.TRACK }
                        if (tracks.isNotEmpty()) {
                            scope.launch { music?.playPlaylist(tracks) }
                        }
                    },
                    onCollection = { item ->
                        scope.launch {
                            val collectionRows = music?.collectionRows(item.id).orEmpty()
                            artistDialogRows = collectionRows
                        }
                    },
                    onOptions = {},
                )
            }
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
                onPageProgress = viewModel::onPageProgress,
                onToggleOverlay = viewModel::toggleOverlay,
                onRetry = viewModel::retry,
                onBack = { navController.popBackStack() },
                onChapterChange = { index ->
                    navController.navigate(Routes.mangaReader(entryId, index)) {
                        popUpTo(navEntry.destination.id) { inclusive = true }
                    }
                },
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

            LaunchedEffect(Unit) {
                musicViewModel?.pause()
            }

            EpisodePlayerRoute(
                entry = entry,
                unitIndex = unitIndex,
                libraryRepository = libraryRepository,
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

            var optionsSong by remember { mutableStateOf<MusicRow?>(null) }
            var selectedAlbum by rememberSaveable(stateSaver = MusicRowSaver) { mutableStateOf<MusicRow?>(null) }
            var albumTracks by remember { mutableStateOf(emptyList<MusicRow>()) }
            var albumLoading by remember { mutableStateOf(false) }
            var albumError by remember { mutableStateOf<String?>(null) }
            val albumScope = rememberCoroutineScope()
            fun playAlbum(index: Int = 0) {
                val album = selectedAlbum
                val albumArtist = album?.subtitle
                albumScope.launch {
                    try {
                        val tracksToPlay = albumTracks.map { track ->
                            if (track.artistHint().isNullOrBlank() && !albumArtist.isNullOrBlank()) {
                                track.copy(subtitle = albumArtist)
                            } else track
                        }
                        music.playPlaylist(tracksToPlay, index)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        albumError = "Could not start playback. Try another song."
                    }
                }
            }
            androidx.compose.runtime.LaunchedEffect(selectedAlbum) {
                val album = selectedAlbum ?: return@LaunchedEffect
                albumTracks = emptyList()
                albumError = null
                albumLoading = true
                try {
                    albumTracks = music.collectionRows(album.id).filter { it.kind == MusicRow.Kind.TRACK }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    albumError = "Could not load this album. Close it and try again."
                } finally {
                    albumLoading = false
                }
            }

            var selectedArtist by rememberSaveable(stateSaver = MusicRowSaver) { mutableStateOf<MusicRow?>(null) }
            var artistDetails by remember { mutableStateOf<com.auris.app.data.remote.music.MusicArtistDetails?>(null) }
            var artistRows by remember { mutableStateOf<List<MusicRow>>(emptyList()) }
            var isArtistLoading by remember { mutableStateOf(false) }
            val artistScope = rememberCoroutineScope()

            androidx.compose.runtime.LaunchedEffect(selectedArtist) {
                val artist = selectedArtist ?: return@LaunchedEffect
                artistDetails = null
                artistRows = emptyList()
                isArtistLoading = true
                try {
                    val (details, rows) = music.loadArtist(artist)
                    artistDetails = details
                    artistRows = rows
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // best-effort
                } finally {
                    isArtistLoading = false
                }
            }

            MusicBrowseScreen(
                state = musicState,
                onQueryChange = music::search,
                onPlay = music::play,
                onOpenAlbum = { selectedAlbum = it },
                onOpenArtist = { selectedArtist = it },
                onTogglePlayPause = music::togglePlayPause,
                onPrevious = music::previous,
                onNext = music::next,
                onOptions = { optionsSong = it },
                onOpenPlayer = { navController.navigate(Routes.PLAYER) },
            )
            selectedAlbum?.takeIf { activeEntry.value?.destination?.route == Routes.MUSIC_BROWSE }?.let { album ->
                com.auris.app.ui.music.MusicAlbumPage(
                    title = album.title, tracks = albumTracks, loading = albumLoading, message = albumError ?: musicState.message,
                    onBack = { selectedAlbum = null },
                    onPlayAll = { playAlbum() },
                    onPlay = { index -> playAlbum(index) },
                    onOptions = { optionsSong = it },
                    isPlaylist = album.kind == MusicRow.Kind.PLAYLIST,
                    albumArtist = album.subtitle,
                    artworkUrl = album.artworkUrl,
                    bottomBar = {
                        if (musicState.nowPlayingId != null) com.auris.app.ui.music.NowPlayingStrip(
                            title = musicState.nowPlayingTitle ?: "Now playing", artist = musicState.nowPlayingArtist,
                            artworkUrl = musicState.nowPlayingArtworkUrl, isPlaying = musicState.isPlaying,
                            isLoading = musicState.isResolving || musicState.isBuffering,
                            onClick = { if (!musicState.isResolving) { navController.navigate(Routes.PLAYER) { launchSingleTop = true } } },
                            onTogglePlayPause = music::togglePlayPause, onPrevious = music::previous, onNext = music::next,
                            canGoPrevious = !musicState.isResolving && musicState.canGoPrevious,
                            canGoNext = !musicState.isResolving && musicState.canGoNext,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    },
                )
            }
            selectedArtist?.takeIf { activeEntry.value?.destination?.route == Routes.MUSIC_BROWSE && selectedAlbum == null }?.let { artistRow ->
                MusicArtistPage(
                    name = artistDetails?.name ?: artistRow.artistHint().orEmpty().ifBlank { artistRow.title },
                    artwork = artistDetails?.photoUrl ?: artistRows.firstOrNull { it.artworkUrl != null }?.artworkUrl ?: artistRow.artworkUrl,
                    bio = artistDetails?.bio,
                    rows = artistRows.distinctBy { it.kind to it.id },
                    loading = isArtistLoading,
                    message = null,
                    onBack = { selectedArtist = null },
                    onPlay = { item -> music.play(item) },
                    onPlayAll = {
                        val tracks = artistRows.filter { it.kind == MusicRow.Kind.TRACK }
                        if (tracks.isNotEmpty()) {
                            artistScope.launch { music.playPlaylist(tracks) }
                        }
                    },
                    onCollection = { item -> selectedAlbum = item },
                    onOptions = { optionsSong = it },
                    bottomBar = {
                        if (musicState.nowPlayingId != null) com.auris.app.ui.music.NowPlayingStrip(
                            title = musicState.nowPlayingTitle ?: "Now playing", artist = musicState.nowPlayingArtist,
                            artworkUrl = musicState.nowPlayingArtworkUrl, isPlaying = musicState.isPlaying,
                            isLoading = musicState.isResolving || musicState.isBuffering,
                            onClick = { if (!musicState.isResolving) { navController.navigate(Routes.PLAYER) { launchSingleTop = true } } },
                            onTogglePlayPause = music::togglePlayPause, onPrevious = music::previous, onNext = music::next,
                            canGoPrevious = !musicState.isResolving && musicState.canGoPrevious,
                            canGoNext = !musicState.isResolving && musicState.canGoNext,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    },
                )
            }
            optionsSong?.let { song ->
                com.auris.app.ui.music.MusicSongActions(song, music) { optionsSong = null }
            }
        }

        // Settings. Diagnostics moved under it: a diagnostics screen reached from an
        // extensions button was a placeholder for a settings screen that did not exist yet.
        composable(Routes.SETTINGS) {
            val updateChecker = appUpdateChecker ?: remember {
                com.auris.app.data.remote.updater.AppUpdateChecker(navController.context.applicationContext)
            }
            val settingsViewModel: SettingsViewModel = viewModel(
                key = "settings",
                factory = SettingsViewModel.factory(
                    extensionRepository = extensionRepository,
                    repoStore = extensionRepoStore,
                    authRepository = authRepository,
                    appUpdateChecker = updateChecker,
                ),
            )
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()

            SettingsScreen(
                state = settingsState,
                onRepoUrlChange = settingsViewModel::setRepoUrl,
                onBeginRepoEdit = settingsViewModel::beginRepoEdit,
                onCancelRepoEdit = settingsViewModel::cancelRepoEdit,
                onFingerprintChange = settingsViewModel::setSigningKeyFingerprint,
                onResetRepo = settingsViewModel::resetRepo,
                onOpenExtensions = { navController.navigate(Routes.EXTENSIONS) },
                onOpenDiagnostics = { navController.navigate(Routes.diagnostics) },
                onSignOut = settingsViewModel::signOut,
                onDeleteAccount = settingsViewModel::deleteAccount,
                onCheckForUpdates = { settingsViewModel.checkForUpdates(force = true) },
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
                ),
            )
            val catalogueState by catalogueViewModel.state.collectAsStateWithLifecycle()

            // The ViewModel emits a transient preview (nothing is persisted); navigation
            // itself stays here in the UI layer — no NavController in the ViewModel.
            LaunchedEffect(catalogueViewModel) {
                catalogueViewModel.openDetail.collectLatest { preview ->
                    val previewId = java.util.UUID.randomUUID().toString()
                    previewItems[previewId] = preview
                    navController.navigate(Routes.animePreview(previewId))
                }
            }

            CatalogueScreen(
                state = catalogueState,
                onItemClick = catalogueViewModel::onItemClick,
                onModeChange = catalogueViewModel::selectMode,
                onQueryChange = catalogueViewModel::onQueryChange,
                onRetry = catalogueViewModel::retry,
                onSourceChange = catalogueViewModel::selectSource,
                onLoadMore = catalogueViewModel::loadMore,
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
                onSeasonClick = { season -> viewModel.openSeason(season) { id ->
                    navController.navigate(Routes.animeDetail(id))
                } },
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

        // Unsaved catalogue preview: same screen, transient data, explicit Save.
        composable(
            route = Routes.ANIME_PREVIEW,
            arguments = listOf(navArgument("previewId") { type = NavType.StringType }),
        ) { navEntry ->
            val previewId = navEntry.arguments?.getString("previewId").orEmpty()
            val preview = previewItems[previewId]

            if (preview == null) {
                // Stale or restored entry whose transient payload is gone.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                return@composable
            }

            val viewModel: MediaDetailViewModel = viewModel(
                key = "preview-$previewId",
                factory = MediaDetailViewModel.previewFactory(
                    preview = preview,
                    libraryRepository = libraryRepository,
                    identifier = kitsuIdentifier,
                    contentRepository = extensionContentRepository,
                ),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()

            MediaDetailScreen(
                state = state,
                onSave = viewModel::savePreview,
                onSeasonClick = { season -> viewModel.openSeason(season) { id ->
                    navController.navigate(Routes.animeDetail(id))
                } },
                onUnitClick = { unit ->
                    val savedId = state.savedEntryId ?: return@MediaDetailScreen
                    viewModel.onUnitClick(
                        unit = unit,
                        onNavigateToUnit = { index ->
                            navController.navigate(Routes.mangaReader(savedId, index))
                        },
                        onPlay = { played, _ ->
                            navController.navigate(Routes.episodePlayer(savedId, played.index))
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
