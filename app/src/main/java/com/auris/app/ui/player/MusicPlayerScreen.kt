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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
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
        // Nothing queued is a real state — reached by opening the player from the pill before
        // anything has played. A full transport UI over blank metadata looked broken, so it
        // gets its own screen with the one action that helps: go back and pick something.
        if (state.error != null) {
            EmptyPlayer(
                title = "Playback unavailable",
                detail = state.error,
                onBack = onBack,
            )
            return@ArtworkGradientBackground
        }

        if (state.isEmpty) {
            EmptyPlayer(
                title = "Nothing is playing",
                detail = "Pick a track from Music or Search and it will appear here.",
                onBack = onBack,
            )
            return@ArtworkGradientBackground
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The nav pill is hidden on this route, so the screen carries its own way out.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerCloseButton(onBack)
            }

            Spacer(Modifier.height(8.dp))

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

            Spacer(Modifier.height(14.dp))

            // ---- Seek bar -------------------------------------------------------
            // 10.5dp extra inset on top of the 20.5dp column padding lands on the
            // measured 31dp.
            AurisSlider(
                progress = state.progress,
                onProgressChange = onSeekFraction,
                contentDescription = "Seek",
                modifier = Modifier.padding(horizontal = 10.5.dp),
            )

            Spacer(Modifier.height(10.dp))

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

            Spacer(Modifier.height(16.dp))

            // ---- Track metadata, centred ---------------------------------------
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                ),
                color = AurisOnSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
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

            Spacer(Modifier.height(18.dp))

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
                onPrevious = onPrevious,
                onNext = onNext,
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

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Previous / play-pause / next.
 *
 * The ±10/30s seek buttons that used to sit here were the only transport controls that
 * disappeared: skipping a track is the action this player is built around, and the queue makes
 * it meaningful. Play/pause remains the only heavy-weight control: a 76dp white disc.
 */
@Composable
private fun TransportTier(
    state: PlayerUiState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Previous track",
            enabled = state.queueSize > 0,
            onClick = onPrevious,
        )

        PlayPauseButton(
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            onClick = onPlayPause,
        )

        TransportButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Next track",
            enabled = state.queueSize > 1,
            onClick = onNext,
        )
    }
}

/** A 56dp hit target around a large transport glyph. */
@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AurisOnSurface else AurisOnSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(42.dp),
        )
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

/** The screen's own way back, since the navigation pill is not shown over the player. */
@Composable
private fun PlayerCloseButton(onClick: () -> Unit) {
    Icon(
        Icons.Filled.KeyboardArrowDown,
        contentDescription = "Close player",
        tint = AurisOnSurface,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/**
 * Shown when the queue is empty or the service is unreachable.
 *
 * Deliberately not the transport UI with blank text: a play button that cannot play is worse
 * than no play button.
 */
@Composable
private fun EmptyPlayer(
    title: String,
    detail: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerCloseButton(onBack)
        }

        Spacer(Modifier.weight(1f))

        Icon(
            Icons.Filled.Album,
            contentDescription = null,
            tint = AurisOnSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = AurisOnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
    }
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
            onCycleRepeat = {},
            onToggleShuffle = {},
        )
    }
}