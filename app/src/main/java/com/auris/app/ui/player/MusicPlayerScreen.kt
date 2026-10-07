package com.auris.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auris.app.ui.components.AurisSlider
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.PlayerGhostIcon
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.ArtworkShapeLarge
import com.auris.app.ui.theme.AurisTheme
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * Full-screen music player.
 *
 * ### Layout, per `UI/music-player-UI.jpg` (1080x2400 px @ 2.625x → 411x914 dp)
 * | Element | Measured (px) | Here |
 * |---|---|---|
 * | Album art | 972x972 at x 54..1025, y 161..1132 | 370dp square, 20.5dp side inset, `aspectRatio(1f)` |
 * | Art corner radius | ~80 | 32dp (`ArtworkShapeLarge`) |
 * | Art → seek bar | 76 | 29dp |
 * | Seek track | 21 tall, x 81..998 | 8dp, 31dp horizontal inset |
 * | Timestamps | baseline ~1080 | row under the seek bar |
 * | Title / artist | ~1160 / ~1245 | 30sp heavy, then 16sp, both centred |
 * | Tier 1 ghost icons | ~1390, four | `SpaceBetween` ghost row |
 * | Tier 2 transport | ~1610 | skip / play / skip + two dot markers |
 * | Volume | ~1745 | speaker / 6dp slider / speaker |
 *
 * Per the product rules there is deliberately **no audio-quality or bitrate badge**
 * between the metadata and the secondary icon row.
 */
@Composable
fun MusicPlayerScreen(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenQueue: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
) {
    // The screen's gradient tracks the current album art.
    val palette = rememberArtworkPaletteValue(artworkKey = state.artworkUrl)

    ArtworkGradientBackground(
        palette = palette,
        // Slightly stronger than other screens: the player is the art showcase.
        intensity = 1.15f,
        fadeEndFraction = 0.62f,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // ---- Album art ------------------------------------------------------
            MediaArtwork(
                imageUrl = state.artworkUrl,
                contentDescription = state.album.ifBlank { state.title },
                shape = ArtworkShapeLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                placeholderIcon = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Album,
                            contentDescription = null,
                            tint = AurisOnSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(72.dp),
                        )
                    }
                },
            )

            Spacer(Modifier.height(29.dp))

            // ---- Seek bar -------------------------------------------------------
            // 10.5dp extra inset on top of the 20.5dp column padding lands on the
            // measured 31dp.
            AurisSlider(
                progress = state.progress,
                onProgressChange = onSeekFraction,
                contentDescription = "Seek",
                modifier = Modifier.padding(horizontal = 10.5.dp),
            )

            Spacer(Modifier.height(14.dp))

            // ---- Timestamps -----------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.positionMs.toTimestamp(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AurisOnSurface,
                )
                Text(
                    text = state.durationMs.toTimestamp(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AurisOnSurface,
                )
            }

            Spacer(Modifier.height(30.dp))

            // ---- Track metadata, centred ---------------------------------------
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 30.sp,
                    lineHeight = 36.sp,
                ),
                color = AurisOnSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = listOf(state.artist, state.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" – "),
                style = MaterialTheme.typography.bodyLarge,
                color = AurisOnSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(38.dp))

            // ---- Tier 1: secondary / utility controls --------------------------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGhostIcon(
                    onClick = onOpenEqualizer,
                    icon = Icons.Filled.Equalizer,
                    contentDescription = "Equalizer",
                )
                PlayerGhostIcon(
                    onClick = onOpenSleepTimer,
                    icon = Icons.Filled.Bedtime,
                    contentDescription = "Sleep timer",
                )
                PlayerGhostIcon(
                    onClick = onOpenDevices,
                    icon = Icons.Filled.Cast,
                    contentDescription = "Devices",
                )
                PlayerGhostIcon(
                    onClick = onOpenQueue,
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                )
            }

            Spacer(Modifier.weight(1f))

            // ---- Tier 2: transport ----------------------------------------------
            TransportTier(
                state = state,
                onPlayPause = onPlayPause,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
            )

            Spacer(Modifier.weight(1f))

            // Shuffle / repeat are stateful toggles, so they sit with the transport
            // tier rather than in the neutral ghost row.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGhostIcon(
                    onClick = onToggleShuffle,
                    icon = Icons.Filled.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffleEnabled) AurisAccent else AurisOnSurfaceVariant,
                )
                Text(
                    text = when (state.repeatMode) {
                        RepeatMode.OFF -> ""
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.repeatMode != RepeatMode.OFF) {
                        AurisAccent
                    } else {
                        Color.Transparent
                    },
                )
                PlayerGhostIcon(
                    onClick = onCycleRepeat,
                    icon = Icons.Filled.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeatMode != RepeatMode.OFF) {
                        AurisAccent
                    } else {
                        AurisOnSurfaceVariant
                    },
                )
            }

            Spacer(Modifier.height(28.dp))

            // ---- Volume ---------------------------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeDown,
                    contentDescription = null,
                    tint = AurisOnSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                AurisSlider(
                    progress = state.volume,
                    onProgressChange = onVolumeChange,
                    contentDescription = "Volume",
                    trackHeight = 6.dp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
                )
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = AurisOnSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * Play/pause flanked by the two dot markers from the reference, with ±10/30s skips.
 * Play/pause is the only heavy-weight control: a 76dp white disc.
 */
@Composable
private fun TransportTier(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DotMarker()

        Icon(
            imageVector = Icons.Filled.Replay10,
            contentDescription = "Rewind 10 seconds",
            tint = AurisOnSurface,
            modifier = Modifier.size(38.dp),
        )

        PlayPauseButton(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onClick = onPlayPause,
        )

        Icon(
            imageVector = Icons.Filled.Forward30,
            contentDescription = "Forward 30 seconds",
            tint = AurisOnSurface,
            modifier = Modifier.size(38.dp),
        )

        DotMarker()
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isBuffering -> CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = AurisBackground,
                strokeWidth = 3.dp,
            )

            isPlaying -> Icon(
                Icons.Filled.Pause,
                contentDescription = "Pause",
                tint = AurisBackground,
                modifier = Modifier.size(46.dp),
            )

            else -> Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = AurisBackground,
                modifier = Modifier.size(46.dp),
            )
        }
    }
}

/** The small circular markers bracketing the transport row in the reference UI. */
@Composable
private fun DotMarker(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(11.dp)
            .clip(CircleShape)
            .background(AurisOnSurfaceVariant.copy(alpha = 0.65f)),
    )
}

@Preview(widthDp = 411, heightDp = 914)
@Composable
private fun MusicPlayerScreenPreview() {
    AurisTheme {
        MusicPlayerScreen(
            state = PlayerUiState(
                title = "12 Fractures",
                artist = "Pierce The Veil",
                album = "The Jaws of Life",
                isPlaying = true,
                positionMs = 21_000,
                durationMs = 182_000,
                volume = 0.72f,
            ),
            onPlayPause = {},
            onSeekFraction = {},
            onVolumeChange = {},
            onSkipBack = {},
            onSkipForward = {},
            onCycleRepeat = {},
            onToggleShuffle = {},
        )
    }
}