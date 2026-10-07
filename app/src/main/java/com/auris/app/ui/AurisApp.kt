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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.KitsuIdentifier
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.ui.player.EpisodePlayer
import androidx.lifecycle.ViewModelProvider
import com.auris.app.source.repo.ExtensionRepo
import com.auris.app.ui.components.FloatingNavigationBar
import com.auris.app.ui.navigation.AurisNavHost
import com.auris.app.ui.navigation.Routes
import com.auris.app.ui.navigation.TopLevelDestination
import kotlinx.coroutines.flow.Flow

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
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val library by repository.observeLibrary()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pending by pendingSyncCount.collectAsStateWithLifecycle(initialValue = 0)

    val showNavBar = TopLevelDestination.entries.any { it.route == currentRoute }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        AurisNavHost(
            navController = navController,
            library = library,
            onEntryClick = { entry -> navController.navigate(entry.destinationRoute()) },
            libraryRepository = libraryRepository,
            kitsuIdentifier = kitsuIdentifier,
            extensionContentRepository = extensionContentRepository,
            episodePlayer = episodePlayer,
            appStateCollector = appStateCollector,
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
                currentRoute = currentRoute,
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        // Single-top with state restoration: moving between tabs must not
                        // reset the previous tab's scroll position.
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
            visible = showNavBar && pending > 0,
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
                    text = if (pending == 1) "1 change pending" else "$pending changes pending",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Routes an entry to its pillar-specific destination. */
private fun LibraryEntryEntity.destinationRoute(): String = when (mediaType) {
    MediaType.ANIME -> Routes.animeDetail(id)
    MediaType.MANGA -> Routes.mangaReader(id, currentUnitIndex)
    MediaType.MUSIC -> Routes.player(id)
}