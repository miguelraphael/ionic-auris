package com.auris.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.repository.AuthRepository
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.KitsuIdentifier
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.ui.auth.AuthScreen
import com.auris.app.ui.auth.AuthViewModel
import com.auris.app.ui.player.EpisodePlayer
import androidx.lifecycle.ViewModelProvider
import com.auris.app.source.repo.ExtensionRepo
import com.auris.app.ui.components.FloatingNavigationBar
import com.auris.app.ui.components.LocalIsOnline
import com.auris.app.ui.components.OfflineBanner
import com.auris.app.ui.components.SessionExpiredDialog
import com.auris.app.ui.navigation.AurisNavHost
import com.auris.app.ui.navigation.Routes
import com.auris.app.ui.navigation.TopLevelDestination
import com.auris.app.ui.theme.AurisBackdrop
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisLogo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The app shell.
 *
 * ### Structure
 * A `Box`, not a `Scaffold`. A Scaffold reserves a bottom slot and lays the navigation bar
 * edge-to-edge, which defeats the floating-pill requirement. Instead the NavHost fills the
 * window, screens pad their own content, and the pill floats above scrolling artwork while
 * still respecting the gesture-bar inset.
 *
 * ### State
 * The library is collected once here and handed down, so every screen shares a single Room
 * Flow subscription and a single snapshot for the whole window.
 */
@Composable
fun AurisApp(
    repository: LibraryRepository,
    pendingSyncCount: Flow<Int>,
    /**
     * Supplied by the container. Optional so previews and tests can render the shell without
     * standing up the extension stack; the extensions route is simply absent then.
     */
    libraryRepository: LibraryRepository,
    kitsuIdentifier: KitsuIdentifier,
    extensionContentRepository: ExtensionContentRepository,
    extensionsViewModelFactory: ViewModelProvider.Factory? = null,
    extensionRepo: ExtensionRepo = ExtensionRepo.DEFAULT,
    /** Process-scoped so the player survives navigation between episodes. */
    episodePlayer: EpisodePlayer? = null,
    appStateCollector: com.auris.app.diagnostics.AppStateCollector? = null,
    musicSource: com.auris.app.data.remote.music.MusicSource? = null,
    /**
     * The Invidious instance list, shared with the audio source.
     *
     * Required, not nullable: the resolver reads it per track, so a second store would let
     * Settings and playback disagree about which instances exist.
     */
    invidiousInstanceStore: com.auris.app.data.repository.InvidiousInstanceStore,
    extensionRepository: com.auris.app.data.repository.ExtensionRepository,
    extensionRepoStore: com.auris.app.data.repository.ExtensionRepoStore,
    extensionLoader: com.auris.app.source.ExtensionLoader,
    /**
     * Mandatory gate: the library syncs per user, so a null session routes to
     * the login screen before anything else can render. Required, not
     * nullable, for the same reason the detail screen's repositories are.
     */
    authRepository: com.auris.app.data.repository.AuthRepository,
    /** Process-scoped connectivity; absent in previews, where the app is treated as online. */
    networkMonitor: com.auris.app.core.network.NetworkMonitor? = null,
    modifier: Modifier = Modifier,
) {
    // Gate readiness first: the initial null is "not loaded yet", which is
    // indistinguishable from "signed out" — showing the login screen on it
    // would flash it at every signed-in cold start.
    var gateReady by remember { mutableStateOf(false) }
    val session by authRepository.session
        .collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(Unit) {
        authRepository.session.first()
        gateReady = true
    }

    val sessionExpired by authRepository.sessionExpired
        .collectAsStateWithLifecycle(initialValue = false)
    var sessionExpiredDismissed by remember { mutableStateOf(false) }

    // Connectivity is a modifier on the shell, not a gate: the library is local and keeps
    // working without it.
    val onlineFlow = remember(networkMonitor) { networkMonitor?.isOnline ?: flowOf(true) }
    val isOnline by onlineFlow.collectAsStateWithLifecycle(initialValue = true)

    val scope = rememberCoroutineScope()

    // A new session (or a sign-out) makes the dialog relevant again.
    LaunchedEffect(session) { sessionExpiredDismissed = false }

    if (!gateReady) {
        // Splash: the same OLED + brand backdrop and the same lockup the gate shows, so the
        // handoff from the system splash to the login screen is one continuous frame.
        AurisBackdrop(modifier = modifier) {
            AurisLogo(
                includeWordmark = true,
                size = 148.dp,
                background = AurisBackground,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        return
    }

    if (session == null) {
        CompositionLocalProvider(LocalIsOnline provides isOnline) {
            Box(Modifier.fillMaxSize()) {
                AuthGate(authRepository = authRepository, modifier = Modifier.fillMaxSize())
                OfflineOverlay(
                    visible = !isOnline,
                    message = "No internet \u2014 signing in needs a connection",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp),
                )
            }
        }
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Null until the first Room emission. That is the only way to tell a cold start from a
    // genuinely empty library, and it is what turns the empty state into a skeleton.
    val libraryState by repository.observeLibrary()
        .collectAsStateWithLifecycle(initialValue = null)
    val library = libraryState.orEmpty()
    val isLibraryLoading = libraryState == null
    val pending by pendingSyncCount.collectAsStateWithLifecycle(initialValue = 0)

    val navigationRoute = if (currentRoute == Routes.MUSIC_BROWSE) TopLevelDestination.MUSIC.route else currentRoute
    val showNavBar = TopLevelDestination.entries.any { it.route == navigationRoute }

    CompositionLocalProvider(
        LocalIsOnline provides isOnline,
        com.auris.app.ui.components.LocalTopLevelNavigate provides { destination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            AurisBackdrop(modifier = Modifier.fillMaxSize()) {
                AurisNavHost(
                    navController = navController,
                    library = library,
                    onEntryClick = { entry -> navController.navigate(entry.destinationRoute()) },
                    isLibraryLoading = isLibraryLoading,
                    libraryRepository = libraryRepository,
                    kitsuIdentifier = kitsuIdentifier,
                    extensionContentRepository = extensionContentRepository,
                    episodePlayer = episodePlayer,
                    appStateCollector = appStateCollector,
                    musicSource = musicSource,
                    invidiousInstanceStore = invidiousInstanceStore,
                    extensionRepository = extensionRepository,
                    extensionRepoStore = extensionRepoStore,
                    extensionLoader = extensionLoader,
                    authRepository = authRepository,
                    extensionsViewModelFactory = extensionsViewModelFactory,
                    extensionRepo = extensionRepo,
                    modifier = Modifier.fillMaxSize(),
                )

                // Floating pill, drawn last so it sits above all content.
                AnimatedVisibility(
                    visible = showNavBar,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 2 },
                    exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 2 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    FloatingNavigationBar(
                        destinations = TopLevelDestination.entries.toList(),
                        currentRoute = navigationRoute,
                        onNavigate = { destination ->
                            navController.navigate(destination.route) {
                                // Single-top with state restoration: moving between tabs must
                                // not reset the previous tab's scroll position.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }

                // The only global chrome beyond navigation: how many local changes are still
                // waiting for a network. Sits just above the pill, right-aligned.
                AnimatedVisibility(
                    visible = showNavBar && navigationRoute != TopLevelDestination.MUSIC.route && pending > 0,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = 108.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            text = if (pending == 1) {
                                "1 change waiting to sync"
                            } else {
                                "$pending changes waiting to sync"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            OfflineOverlay(
                visible = !isOnline,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp),
            )
        }
    }

    SessionExpiredDialog(
        visible = sessionExpired && !sessionExpiredDismissed,
        email = session?.email,
        onSignInAgain = {
            sessionExpiredDismissed = true
            scope.launch { authRepository.signOut() }
        },
        onDismiss = { sessionExpiredDismissed = true },
    )
}

/**
 * The global offline strip.
 *
 * Deliberately an overlay rather than a row above the NavHost: every screen reserves its own
 * top inset, and inserting a strip there would shift each screen's layout by its height when
 * connectivity changed.
 */
@Composable
private fun OfflineOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    message: String = "No internet \u2014 showing saved content",
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        OfflineBanner(message = message)
    }
}

/** Routes an entry to its pillar-specific destination. */
private fun LibraryEntryEntity.destinationRoute(): String = when (mediaType) {
    MediaType.ANIME -> Routes.animeDetail(id)
    MediaType.MANGA -> Routes.animeDetail(id)
    MediaType.MUSIC -> Routes.PLAYER
}

/**
 * The mandatory gate: no session, no app. Success needs no callback — the
 * repository persists the session and this same flow re-emits, routing into
 * the shell on its own.
 */
@Composable
private fun AuthGate(
    authRepository: AuthRepository,
    modifier: Modifier = Modifier,
) {
    val authViewModel: AuthViewModel = viewModel(
        key = "auth",
        factory = AuthViewModel.factory(authRepository),
    )
    val authState by authViewModel.state.collectAsStateWithLifecycle()

    // The ViewModel is Activity-scoped and survives a sign-out, so clearing the transient state
    // here is what lets the user sign back in without killing the app. See [AuthViewModel.onGateShown].
    LaunchedEffect(Unit) { authViewModel.onGateShown() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AuthScreen(
            state = authState,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onToggleMode = authViewModel::toggleMode,
            onSubmit = authViewModel::submit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
