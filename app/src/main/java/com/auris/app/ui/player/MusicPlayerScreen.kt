package com.auris.app.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import com.auris.app.ui.theme.AurisMotion
import com.auris.app.ui.theme.pressFeedback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auris.app.data.remote.music.nowPlayingArtworkUrl
import com.auris.app.ui.components.AurisSlider
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.PlayerGhostIcon
import com.auris.app.ui.theme.AurisActive
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisSurfaceOpaque
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
 * | Art corner radius | ~80 | 24dp (`ArtworkShapeLarge`) |
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
    onOpenSleepTimer: () -> Unit = {},
    onArtistClick: ((artist: String, trackId: String?) -> Unit)? = null,
    onRetry: () -> Unit = {},
) {
    // The screen's gradient tracks the current album art.
    val palette = rememberArtworkPaletteValue(artworkKey = state.artworkUrl)
    val artworkScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.96f,
        animationSpec = tween(240, easing = AurisMotion.Ease),
        label = "playbackArtworkScale",
    )

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
        if (state.error != null || state.isReconnecting) {
            EmptyPlayer(
                title = if (state.isReconnecting) "Connecting to playback" else "Playback unavailable",
                detail = state.error ?: "Reconnecting to playback…",
                trackTitle = state.title,
                onRetry = onRetry,
                isReconnecting = state.isReconnecting,
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

        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            val compact = maxHeight < 700.dp
            val artworkSize = minOf(maxWidth - 41.dp, maxHeight * if (compact) 0.20f else 0.30f).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The nav pill is hidden on this route, so the screen carries its own way out.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerCloseButton(onBack)
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(8.dp))

                    if (compact) { PlayerTrackTitle(state.title) }

                    // ---- Album art ------------------------------------------------------
                    MediaArtwork(
                        imageUrl = nowPlayingArtworkUrl(state.artworkUrl),
                        fallbackImageUrl = state.artworkUrl,
                        contentDescription = state.album.ifBlank { state.title },
                        shape = ArtworkShapeLarge,
                        modifier = Modifier
                            .size(artworkSize)
                            .clearAndSetSemantics { contentDescription = "Album artwork" }
                            .graphicsLayer { scaleX = artworkScale; scaleY = artworkScale }
                            // Lifts the square off the artwork-tinted background, the way the reference
                            // art sits above the sheet. Shadow only: the art itself stays unclipped.
                            .shadow(
                                elevation = 24.dp,
                                shape = ArtworkShapeLarge,
                                clip = false,
                            ),
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

                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // ---- Track metadata, centred ---------------------------------------
                        if (!compact) { PlayerTrackTitle(state.title) }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (state.artist.isNotBlank()) {
                                Text(
                                    text = state.artist,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (onArtistClick != null) AurisAccent else AurisOnSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable(enabled = onArtistClick != null) {
                                            onArtistClick?.invoke(state.artist, state.entryId)
                                        }
                                        .padding(horizontal = 6.dp, vertical = 12.dp),
                                )
                            }
                            if (state.album.isNotBlank()) {
                                Text(
                                    text = state.album,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AurisOnSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

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

                    }
                }

                Spacer(Modifier.height(18.dp))

                // ---- Tier 1: secondary / utility controls --------------------------
                // Sleep, repeat, shuffle, queue. The equalizer and cast buttons are gone: Auris
                // has no equalizer and no remote playback target, so they were dead ends.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerGhostIcon(
                        modifier = Modifier.background(
                            if (state.sleepRemainingMs > 0) AurisActive.copy(alpha = 0.16f) else AurisSurfaceOpaque, CircleShape),
                        onClick = onOpenSleepTimer,
                        icon = Icons.Filled.Bedtime,
                        contentDescription = if (state.sleepRemainingMs > 0) "Sleep timer: ${state.sleepRemainingMs.toTimestamp()} remaining" else "Sleep timer",
                        tint = if (state.sleepRemainingMs > 0) AurisActive else AurisOnSurfaceVariant,
                    )
                    PlayerGhostIcon(
                        modifier = Modifier.background(
                            if (state.repeatMode != RepeatMode.OFF) AurisActive.copy(alpha = 0.16f) else AurisSurfaceOpaque, CircleShape),
                        onClick = onCycleRepeat,
                        icon = if (state.repeatMode == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = when (state.repeatMode) {
                            RepeatMode.OFF -> "Repeat off"
                            RepeatMode.ALL -> "Repeat all"
                            RepeatMode.ONE -> "Repeat one"
                        },
                        tint = if (state.repeatMode != RepeatMode.OFF) {
                            AurisActive
                        } else {
                            AurisOnSurfaceVariant
                        },
                    )
                    PlayerGhostIcon(
                        modifier = Modifier.background(
                            if (state.shuffleEnabled || state.smartShuffleEnabled) AurisActive.copy(alpha = 0.16f) else AurisSurfaceOpaque, CircleShape),
                        onClick = onToggleShuffle,
                        icon = if (state.smartShuffleEnabled) Icons.Filled.AutoAwesome else Icons.Filled.Shuffle,
                        contentDescription = when {
                            state.smartShuffleEnabled -> "Smart shuffle on"
                            state.shuffleEnabled -> "Shuffle on"
                            else -> "Shuffle off"
                        },
                        tint = if (state.shuffleEnabled || state.smartShuffleEnabled) AurisActive else AurisOnSurfaceVariant,
                    )
                    PlayerGhostIcon(
                        modifier = Modifier.background(AurisSurfaceOpaque, CircleShape),
                        onClick = onOpenQueue,
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ---- Tier 2: transport ----------------------------------------------
                TransportTier(
                    state = state,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                )

                Spacer(Modifier.height(16.dp))

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
}

@Composable
private fun PlayerTrackTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp, lineHeight = 34.sp),
        color = AurisOnSurface,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * Previous / play-pause / next, clustered in the middle.
 *
 * The reference put the three controls on one tight axis and kept the outer slots for
 * decorative dot markers; `SpaceBetween` across the full width spread the glyphs to the screen
 * edges, which read as three unrelated buttons. The cluster is centred between two fixed-width
 * dots, so it stays centred regardless of screen width.
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
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {

        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
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
}

/** A 52dp hit target around a large transport glyph. */
@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp).background(AurisSurfaceOpaque, CircleShape),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AurisOnSurface else AurisOnSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.pressFeedback(interactionSource)
            .size(76.dp)
            .clip(CircleShape)
            .background(AurisAccent)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = when { isBuffering -> 0; isPlaying -> 1; else -> 2 },
            animationSpec = tween(150, easing = AurisMotion.Decelerate),
            label = "transportState",
        ) { transportState ->
            when (transportState) {
                0 -> CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = AurisBackground,
                    strokeWidth = 3.dp,
                )

                1 -> Icon(
                    Icons.Filled.Pause,
                    contentDescription = null,
                    tint = AurisBackground,
                    modifier = Modifier.size(36.dp),
                )

                else -> Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = AurisBackground,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

/** The screen's own way back, since the navigation pill is not shown over the player. */
@Composable
private fun PlayerCloseButton(onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "Close player",
            tint = AurisOnSurface,
            modifier = Modifier.size(28.dp),
        )
    }
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
    trackTitle: String = "",
    onRetry: (() -> Unit)? = null,
    isReconnecting: Boolean = false,
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
                .height(48.dp),
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

        if (trackTitle.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = trackTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.Button(onClick = onRetry, enabled = !isReconnecting) {
                Text(if (isReconnecting) "Reconnecting…" else "Try again")
            }
        }
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
