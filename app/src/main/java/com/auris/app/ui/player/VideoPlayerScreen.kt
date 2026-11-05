package com.auris.app.ui.player

import com.auris.app.ui.theme.pressFeedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack

/**
 * Video player: ExoPlayer surface with tap-to-reveal controls.
 *
 * ### The custom control surface, not `PlayerControlView`
 * `PlayerView`'s built-in controls are a different visual language from the rest of the app, do
 * not accept our radii or colours, and put a permanent gradient scrim over the artwork the
 * gradient-background rule depends on. The controls here are hand-built so they match, and they
 * auto-hide.
 *
 * ### Lifecycle
 * EpisodePlayerRoute owns [EpisodePlayer] and registers its lifecycle observer. This screen
 * binds the current video surface and controls, without becoming a second release owner.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    episodePlayer: EpisodePlayer,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onError: (String) -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    headerActions: @Composable RowScope.() -> Unit = {},
) {
    // The player follows the flow, not a captured reference: `EpisodePlayer` rebuilds it per
    // episode so each stream gets its own request headers, and a captured instance would go
    // stale the moment a second episode is loaded.
    val player by episodePlayer.player.collectAsStateWithLifecycle()
    val isBuffering by episodePlayer.isBuffering.collectAsStateWithLifecycle()

    // EpisodePlayerRoute owns lifecycle and release. A second owner here would release
    // the player when switching between its error view and the video surface.

    // Collected rather than polled: the error arrives from the player's own thread, so reading
    // it once at composition would always capture `null`.
    val error by episodePlayer.error.collectAsStateWithLifecycle()
    LaunchedEffect(error) {
        error?.let {
            onError(it)
            episodePlayer.consumeError()
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var seekFraction by remember { mutableFloatStateOf(-1f) }

    // Re-keyed on the player instance, so switching episodes re-binds and re-reads duration.
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        fun updatePosition() {
            isPlaying = player?.isPlaying == true
            durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }
        updatePosition()
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                // Includes paused seeks, timeline changes, and server switches.
                updatePosition()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                controlsVisible = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = player?.duration?.coerceAtLeast(0L) ?: 0L
                }
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    // Poll position while playing. 500ms is coarse enough to be free and fine enough that the
    // scrubber does not visibly stutter; `isPlaying` gates it so an idle player costs nothing.
    LaunchedEffect(isPlaying, player) {
        while (isPlaying && player != null) {
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            kotlinx.coroutines.delay(500)
        }
    }

    // Auto-hide the controls after a few seconds of playback, as every video player does. Paused
    // keeps them up, because a paused player is exactly when someone wants them.
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            kotlinx.coroutines.delay(3_500)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { controlsVisible = !controlsVisible },
            ),
    ) {
        // Keyed on the player instance so switching episodes rebuilds the surface rather than
        // leaving the old one attached to a released player. `AndroidView` has no `key`
        // parameter, so the key goes on the enclosing block.
        key(player) {
            AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // Custom controls, so the built-in chrome stays off.
                    useController = false
                    // Fit rather than Fill: cropping a 16:9 stream on a tall phone loses the
                    // top and bottom of every shot.
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
            )
        }

        if (isBuffering) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AurisLogo(
                    background = Color.Black,
                    size = 48.dp,
                    alpha = 0.55f,
                )
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 100.dp)
                        .size(24.dp),
                    strokeWidth = 2.dp,
                    color = AurisAccent,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                title = title,
                subtitle = subtitle,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                seekFraction = seekFraction,
                isFullscreen = isFullscreen,
                onBack = onBack,
                headerActions = headerActions,
                onPlayPause = {
                    val exo = player
                    if (exo == null) return@PlayerControls
                    if (exo.isPlaying) exo.pause() else {
                        // Re-arm the auto-hide: a resumed player should hide its controls again.
                        controlsVisible = true
                        exo.play()
                    }
                },
                onSeekBy = { deltaMs ->
                    player?.let { exo ->
                        val limit = exo.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0L, limit))
                        positionMs = exo.currentPosition.coerceAtLeast(0L)
                    }
                },
                onSeekFraction = { fraction ->
                    if (durationMs > 0) player?.let { exo ->
                        exo.seekTo((durationMs * fraction).toLong())
                        positionMs = exo.currentPosition.coerceAtLeast(0L)
                    }
                    seekFraction = -1f
                },
                onScrubFraction = { fraction -> seekFraction = fraction },
                onToggleFullscreen = onToggleFullscreen,
            )
        }

        if (!controlsVisible) {
            PlayerWatermark(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun PlayerControls(
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    seekFraction: Float,
    isFullscreen: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onScrubFraction: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    headerActions: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ControlButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = AurisOnSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AurisOnSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            headerActions()
        }
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton(Icons.Filled.Replay10, "Back 10 seconds", { onSeekBy(-10_000L) }, size = 48.dp)
            ControlButton(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (isPlaying) "Pause" else "Play", onPlayPause, size = 64.dp)
            ControlButton(Icons.Filled.Forward10, "Forward 10 seconds", { onSeekBy(10_000L) }, size = 48.dp)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            .padding(horizontal = 20.dp, vertical = 8.dp)) {
            SeekBar(positionMs, durationMs, seekFraction, onScrubFraction, onSeekFraction,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(if (seekFraction >= 0f) (durationMs * seekFraction).toLong() else positionMs),
                    style = MaterialTheme.typography.labelMedium, color = AurisOnSurface)
                Text(" / ${formatTime(durationMs)}", style = MaterialTheme.typography.labelMedium,
                    color = AurisOnSurfaceVariant)
                Spacer(Modifier.weight(1f))
                ControlButton(if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    if (isFullscreen) "Exit fullscreen" else "Fullscreen", onToggleFullscreen)
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    scrubFraction: Float,
    onScrubFraction: (Float) -> Unit,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveFraction = if (durationMs <= 0) 0f else positionMs.toFloat() / durationMs
    val shown = if (scrubFraction >= 0f) scrubFraction else liveFraction

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(shown.coerceIn(0f, 1f), 0f..1f)
                setProgress { fraction ->
                    if (durationMs > 0) {
                        onSeekFraction(fraction.coerceIn(0f, 1f))
                        true
                    } else false
                }
            }
            .pointerInputScrub(onScrubFraction, onSeekFraction),
    ) {
        val trackY = size.height / 2f
        val trackHeight = 4.dp.toPx()

        drawRoundRect(
            color = AurisTrack,
            topLeft = androidx.compose.ui.geometry.Offset(0f, trackY - trackHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2),
        )
        drawRoundRect(
            color = AurisAccent,
            topLeft = androidx.compose.ui.geometry.Offset(0f, trackY - trackHeight / 2),
            size = androidx.compose.ui.geometry.Size(size.width * shown.coerceIn(0f, 1f), trackHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2),
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 44.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(size).pressFeedback(interactionSource),
        interactionSource = interactionSource,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = AurisSurface,
            contentColor = AurisOnSurface,
        ),
    ) {
        Icon(icon, contentDescription, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * Watermark on the player.
 *
 * Light ink over a black video surface, which is fixed regardless of frame content, so the
 * contrast decision never has to be made per frame.
 */
@Composable
private fun PlayerWatermark(modifier: Modifier = Modifier) {
    AurisLogo(background = Color.Black, size = 26.dp, alpha = 0.2f, modifier = modifier)
}

/** `m:ss`, or `h:mm:ss` past an hour. */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Drag-to-scrub on the seek bar. */
@Composable
private fun Modifier.pointerInputScrub(
    onScrubFraction: (Float) -> Unit,
    onSeekFraction: (Float) -> Unit,
): Modifier {
    val currentScrub by rememberUpdatedState(onScrubFraction)
    val currentSeek by rememberUpdatedState(onSeekFraction)
    return this
    // Drag first, so a drag is not consumed as a tap on release — the two gesture detectors
    // compete, and tap-first would fire a seek on every scrub.
    .pointerInput(Unit) {
        var fraction = 0f
        detectHorizontalDragGestures(
            onDragEnd = { currentSeek(fraction) },
            onDragCancel = { currentScrub(-1f) },
            onDragStart = { offset ->
                fraction = (offset.x / size.width).coerceIn(0f, 1f)
                currentScrub(fraction)
            },
            onHorizontalDrag = { change, _ ->
                change.consume()
                fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                currentScrub(fraction)
            },
        )
    }
    .pointerInput(Unit) {
        detectTapGestures(
            onTap = { offset -> currentSeek((offset.x / size.width).coerceIn(0f, 1f)) },
        )
    }

}
