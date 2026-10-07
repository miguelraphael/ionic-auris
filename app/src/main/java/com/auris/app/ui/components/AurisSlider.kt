package com.auris.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisTrack

/**
 * Seek and volume bar matching the reference player: a thin, fully-rounded track with a
 * flat fill and no thumb at rest — the thumb only appears via the ripple-free drag
 * affordance, consistent with the screenshot.
 *
 * Measured from `UI/music-player-UI.jpg`: track height 8dp (21px @2.625x), horizontal
 * insets 31dp, fill `#86B4FF`, track `#252A2E`.
 */
@Composable
fun AurisSlider(
    progress: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 8.dp,
    fillColor: Color = AurisAccent,
    trackColor: Color = AurisTrack,
    contentDescription: String? = null,
) {
    var dragFraction by remember { mutableFloatStateOf(progress) }
    var isDragging by remember { mutableStateOf(false) }

    // While dragging, show the live finger value; otherwise ease toward the player position.
    val target = if (isDragging) dragFraction else progress
    val animatedFraction by animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = if (isDragging) 0 else 180),
        label = "sliderFill",
    )

    val fill = if (enabled) fillColor else fillColor.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                progressBarRangeInfo =
                    ProgressBarRangeInfo(animatedFraction.coerceIn(0f, 1f), 0f..1f)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onProgressChange((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        isDragging = true
                    },
                    onDragEnd = {
                        isDragging = false
                        onProgressChange(dragFraction)
                    },
                    onDragCancel = {
                        isDragging = false
                        onProgressChange(dragFraction)
                    },
                ) { change, dragAmount ->
                    change.consume()
                    dragFraction = (dragFraction + dragAmount / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(CircleShape)
                .background(trackColor),
        )
        Box(
            Modifier
                .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                .height(trackHeight)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

/**
 * Buffered-range variant used when playing adaptive DASH streams: the buffered portion
 * renders in a slightly brighter track colour behind the fill.
 */
@Composable
fun AurisBufferedSlider(
    progress: Float,
    buffered: Float,
    onProgressChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trackHeight: Dp = 8.dp,
    fillColor: Color = AurisAccent,
    trackColor: Color = AurisTrack,
    bufferedColor: Color = AurisOnSurfaceMuted,
    contentDescription: String? = null,
) {
    Box(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth(buffered.coerceIn(0f, 1f))
                .height(trackHeight)
                .clip(CircleShape)
                .background(bufferedColor),
        )
        AurisSlider(
            progress = progress,
            onProgressChange = onProgressChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            trackHeight = trackHeight,
            fillColor = fillColor,
            trackColor = trackColor,
            contentDescription = contentDescription,
        )
    }
}

private val AurisOnSurfaceMuted = Color(0xFF454B57)