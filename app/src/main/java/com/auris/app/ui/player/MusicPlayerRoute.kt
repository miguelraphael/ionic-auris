package com.auris.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.theme.ArtworkShapeSmall
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack

/**
 * The now-playing route: binds a [PlayerViewModel] to the playback service and renders
 * [MusicPlayerScreen].
 *
 * The screen itself stays a pure function of [PlayerUiState]; everything stateful — the
 * controller, the progress tick, the queue — lives here, which is also what lets the preview
 * render the player without a service.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(context))

    val state by viewModel.state.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()

    var showQueue by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    // One-shot messages from controls that cannot act in this build. Reported rather than
    // silently swallowed: a tap that looks broken is worse than one that explains itself.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Box(modifier.fillMaxSize()) {
        MusicPlayerScreen(
            state = state,
            onPlayPause = viewModel::togglePlayPause,
            onSeekFraction = viewModel::seekToFraction,
            onVolumeChange = viewModel::setVolume,
            onCycleRepeat = viewModel::cycleRepeat,
            onToggleShuffle = viewModel::toggleShuffle,
            onBack = onBack,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onOpenQueue = { showQueue = true },
            onOpenDevices = { viewModel.unavailable("Casting") },
            onOpenSleepTimer = { viewModel.unavailable("Sleep timer") },
            onOpenEqualizer = { viewModel.unavailable("The equalizer") },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp),
        )
    }

    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            sheetState = sheetState,
            containerColor = AurisBackground,
        ) {
            QueueSheet(
                items = queue,
                onSelect = { item ->
                    viewModel.playQueueItem(item.index)
                    showQueue = false
                },
            )
        }
    }
}

@Composable
private fun QueueSheet(
    items: List<PlayerQueueItem>,
    onSelect: (PlayerQueueItem) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                color = AurisOnSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${items.size} track${if (items.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = AurisOnSurfaceVariant,
            )
        }

        if (items.isEmpty()) {
            Text(
                text = "Nothing queued yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(items, key = { it.index }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(ArtworkShapeSmall)
                        .background(
                            if (item.isCurrent) {
                                AurisAccent.copy(alpha = 0.10f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(item) },
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MediaArtwork(
                        imageUrl = item.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(42.dp)
                            .background(AurisTrack, ArtworkShapeSmall),
                        shape = ArtworkShapeSmall,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (item.isCurrent) AurisAccent else AurisOnSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!item.artist.isNullOrBlank()) {
                            Text(
                                text = item.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurisOnSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.isCurrent) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Now playing",
                            tint = AurisAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
