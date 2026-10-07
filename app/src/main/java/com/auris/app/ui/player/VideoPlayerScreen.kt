package com.auris.app.ui.player

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
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
 * [EpisodePlayer] is created on first composition and released when the composition leaves.
 * It is additionally registered as a [androidx.lifecycle.LifecycleObserver] so playback pauses on
 * `onStop` — the composition may survive while the activity is backgrounded (another app on top),
 * and a paused-but-alive player keeps the audio focus and the wake lock.
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
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // The player follows the flow, not a captured reference: `EpisodePlayer` rebuilds it per
    // episode so each stream gets its own request headers, and a captured instance would go
    // stale the moment a second episode is loaded.
    val player by episodePlayer.player.collectAsStateWithLifecycle()
    val isBuffering by episodePlayer.isBuffering.collectAsStateWithLifecycle()

    // Registering as an observer is what pauses on backgrounding; `DisposableEffect` only runs on
    // composition disposal, which does not happen when the activity is merely stopped.
    DisposableEffect(lifecycleOwner, episodePlayer) {
        lifecycleOwner.lifecycle.addObserver(episodePlayer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(episodePlayer)
            episodePlayer.release()
        }
    }

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
    var isFullscreen by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(-1f) }

    // Re-keyed on the player instance, so switching episodes re-binds and re-reads duration.
    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) controlsVisible = false
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
            modifier = Modifier.align(Alignment.BottomCenter),
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
                    player?.seekTo(((player?.currentPosition ?: 0L) + deltaMs).coerceAtLeast(0L))
                },
                onSeekFraction = { fraction ->
                    if (durationMs > 0) player?.seekTo((durationMs * fraction).toLong())
                    seekFraction = -1f
                },
                onScrubFraction = { fraction -> seekFraction = fraction },
                onToggleFullscreen = { isFullscreen = !isFullscreen },
            )
        }

        if (controlsVisible) {
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xE0000000))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = AurisOnSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(14.dp))

        // Scrubber. Draggable, with the fraction echoed back so the thumb follows the finger
        // rather than only updating on release.
        SeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            scrubFraction = seekFraction,
            onScrubFraction = onScrubFraction,
            onSeekFraction = onSeekFraction,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = AurisOnSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            ControlButton(
                icon = Icons.Filled.Replay10,
                contentDescription = "Back 10 seconds",
                onClick = { onSeekBy(-10_000L) },
            )
            Spacer(Modifier.width(10.dp))
            ControlButton(
                icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                size = 52.dp,
                onClick = onPlayPause,
            )
            Spacer(Modifier.width(10.dp))
            ControlButton(
                icon = Icons.Filled.Forward10,
                contentDescription = "Forward 10 seconds",
                onClick = { onSeekBy(10_000L) },
            )
            Spacer(Modifier.width(10.dp))
            ControlButton(
                icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                onClick = onToggleFullscreen,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Back",
                style = MaterialTheme.typography.labelLarge,
                color = AurisOnSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(AurisRadii.button))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
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
            .height(28.dp)
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
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AurisSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AurisOnSurface,
            modifier = Modifier.size(size * 0.5f),
        )
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
private fun Modifier.pointerInputScrub(
    onScrubFraction: (Float) -> Unit,
    onSeekFraction: (Float) -> Unit,
): Modifier = this
    // Drag first, so a drag is not consumed as a tap on release — the two gesture detectors
    // compete, and tap-first would fire a seek on every scrub.
    .pointerInput(onScrubFraction) {
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                onScrubFraction((offset.x / size.width).coerceIn(0f, 1f))
            },
            onHorizontalDrag = { change, _ ->
                onScrubFraction((change.position.x / size.width).coerceIn(0f, 1f))
            },
        )
    }
    .pointerInput(onSeekFraction) {
        detectTapGestures(
            onTap = { offset -> onSeekFraction((offset.x / size.width).coerceIn(0f, 1f)) },
        )
    }