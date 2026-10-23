package com.auris.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.roundToInt

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
            onOpenSleepTimer = { viewModel.unavailable("Sleep timer") },
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
                state = state,
                onSelect = { item ->
                    viewModel.playQueueItem(item.index)
                    showQueue = false
                },
                onMoveBy = viewModel::moveQueueItemBy,
                onClear = viewModel::clearUpcoming,
            )
        }
    }
}

/**
 * The queue, styled after the reference: the playing track on top, then the upcoming list with
 * cover art and drag handles.
 *
 * The header exists because a plain list of titles gave no answer to "what is this a queue
 * *of*?". It carries the current track and the playback mode (shuffle / repeat), so the sheet is
 * readable without closing it to look at the player.
 */
@Composable
private fun QueueSheet(
    items: List<PlayerQueueItem>,
    state: PlayerUiState,
    onSelect: (PlayerQueueItem) -> Unit,
    onMoveBy: (String, Int) -> Unit,
    onClear: () -> Unit,
) {
    val current = items.firstOrNull { it.isCurrent }
    val hasUpcoming = current != null && items.any { it.index > current.index }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtwork(
                imageUrl = current?.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    // The playing track is the one piece of art worth lifting off the sheet.
                    .shadow(10.dp, ArtworkShapeSmall, clip = false),
                shape = ArtworkShapeSmall,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = current?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = AurisOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = current?.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurisOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onClear, enabled = hasUpcoming) {
                Text(
                    text = "Clear",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hasUpcoming) AurisAccent else AurisOnSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Up next",
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

        Text(
            text = buildString {
                append(if (state.shuffleEnabled) "Shuffle on" else "Shuffle off")
                append(" · ")
                append(
                    when (state.repeatMode) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = AurisOnSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(top = 2.dp, bottom = 8.dp),
        )

        if (items.isEmpty()) {
            Text(
                text = "Nothing queued yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            items(items, key = { it.index }) { item ->
                QueueRow(
                    item = item,
                    onSelect = { onSelect(item) },
                    onMoveBy = onMoveBy,
                )
            }
        }
    }
}

/** One queue row: art, title/artist, and a drag handle that reorders the queue. */
@Composable
private fun QueueRow(
    item: PlayerQueueItem,
    onSelect: () -> Unit,
    onMoveBy: (String, Int) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { QUEUE_ROW_HEIGHT.toPx() }

    // The row's content changes as items are reordered, so the gesture keeps the id of the item
    // it started on and always calls the latest callback.
    val latestItem by rememberUpdatedState(item)
    val latestMove by rememberUpdatedState(onMoveBy)
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QUEUE_ROW_HEIGHT)
            .background(
                if (item.isCurrent) AurisAccent.copy(alpha = 0.10f) else Color.Transparent,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(
            imageUrl = item.artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .size(46.dp)
                .then(
                    if (item.isCurrent) {
                        Modifier.shadow(8.dp, ArtworkShapeSmall, clip = false)
                    } else {
                        Modifier
                    },
                ),
            shape = ArtworkShapeSmall,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (item.isCurrent) AurisAccent else AurisOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isCurrent) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "Now playing",
                        tint = AurisAccent,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = item.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AurisOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = "Reorder ${item.title}",
            tint = AurisOnSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier
                .size(40.dp)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            draggingId = latestItem.mediaId
                            dragAccum = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            val id = draggingId ?: return@detectDragGestures
                            dragAccum += amount.y
                            // One queue row is a fixed height, so distance maps to positions.
                            val steps = (dragAccum / rowHeightPx).roundToInt()
                            if (steps != 0) {
                                latestMove(id, steps)
                                dragAccum -= steps * rowHeightPx
                            }
                        },
                        onDragEnd = {
                            draggingId = null
                            dragAccum = 0f
                        },
                        onDragCancel = {
                            draggingId = null
                            dragAccum = 0f
                        },
                    )
                }
                .padding(8.dp),
        )
    }
}

/** Fixed, because the drag handle converts pixels to rows with this number. */
private val QUEUE_ROW_HEIGHT = 64.dp
