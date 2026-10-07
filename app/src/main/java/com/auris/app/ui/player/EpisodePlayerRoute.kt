package com.auris.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.source.SourceUnit
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant

/**
 * The episode player route: resolve the stream, then hand it to [EpisodePlayer].
 *
 * ### Why resolution happens again here
 * The detail screen already resolved the stream to decide *whether* one exists, but it
 * deliberately does not keep the result — extension stream URLs are frequently signed and
 * short-lived, so the freshest URL is the one fetched at playback time. One extra request is
 * cheaper than a 403.
 *
 * @param entry and [unit] are passed in rather than looked up: the caller (the NavHost) already
 *   holds them from the library snapshot, and a lookup here would be a second source of truth
 *   for data already in hand.
 * @param episodePlayer process-scoped, so navigating between episodes reuses the instance rather
 *   than rebuilding it on every screen entry.
 */
@Composable
fun EpisodePlayerRoute(
    entry: LibraryEntryEntity,
    unit: SourceUnit,
    contentRepository: ExtensionContentRepository,
    episodePlayer: EpisodePlayer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Registered before playback starts, so an early `onStop` cannot leak a player that has not
    // been created yet.
    DisposableEffect(lifecycleOwner, episodePlayer) {
        lifecycleOwner.lifecycle.addObserver(episodePlayer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(episodePlayer)
            episodePlayer.release()
        }
    }

    LaunchedEffect(entry.id, unit.index, unit.url) {
        contentRepository.fetchStreams(entry, unit)
            .onSuccess { targets ->
                if (targets.isEmpty()) {
                    // Empty is a real answer: the episode is listed but nothing is hosted.
                    statusMessage = "No playable stream for this episode"
                    episodePlayer.play(emptyList())
                } else {
                    statusMessage = null
                    episodePlayer.play(targets)
                }
            }
            .onFailure { error ->
                statusMessage = if (error is ExtensionContentRepository.UnavailableException) {
                    "The extension providing this episode is not installed"
                } else {
                    "Could not resolve a stream. Check your connection and try again."
                }
                episodePlayer.play(emptyList())
            }
    }

    val player by episodePlayer.player.collectAsStateWithLifecycle()

    if (player == null) {
        ResolvingPlaceholder(statusMessage)
        return
    }

    VideoPlayerScreen(
        episodePlayer = episodePlayer,
        title = entry.title,
        subtitle = unit.title,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Shown while the stream resolves, or when there is none.
 *
 * A separate state rather than the player with a hidden surface, because "still working" and
 * "nothing playable" are different answers and the user needs to be told which one this is.
 */
@Composable
private fun ResolvingPlaceholder(message: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            AurisLogo(background = Color.Black, size = 48.dp, alpha = 0.6f)
            Spacer(Modifier.height(20.dp))

            if (message == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = AurisAccent,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Resolving stream…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurisOnSurfaceVariant,
                )
            } else {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AurisOnSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}