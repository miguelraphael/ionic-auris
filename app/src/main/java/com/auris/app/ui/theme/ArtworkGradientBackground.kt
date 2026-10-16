package com.auris.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The global background: the active artwork's dominant colour washed over the shared OLED +
 * brand base, per the aesthetic rules.
 *
 * Three behaviours matter for the reference look:
 *  1. The wash starts behind the top of the content and has faded out by [fadeEndFraction] of
 *     the height, so the lower half falls back to [AurisBackdrop]'s brand sweep.
 *  2. The colour is blended most of the way into the background (see [attenuate]), so a bright
 *     cover tints the top of the screen instead of repainting it. Without this the gradient
 *     read as a solid blue screen and the glass cards on top looked grey.
 *  3. It draws behind everything, including the system bars.
 */
@Composable
fun ArtworkGradientBackground(
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    fadeEndFraction: Float = 0.55f,
    content: @Composable BoxScope.() -> Unit,
) {
    AurisBackdrop(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    val top = palette.primary.attenuate(intensity)
                    val mid = palette.secondary.attenuate(intensity * 0.8f)
                    val endY = size.height * fadeEndFraction

                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to top,
                            0.4f to mix(top, mid, 0.45f),
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = endY,
                        ),
                        size = size,
                    )

                    // A faint radial bloom behind the artwork keeps large covers from
                    // producing a flat band of colour.
                    drawRect(
                        brush = Brush.radialGradient(
                            0f to top.copy(alpha = 0.20f * intensity),
                            1f to Color.Transparent,
                            center = Offset(size.width / 2f, size.height * 0.14f),
                            radius = size.minDimension * 0.85f,
                        ),
                    )
                },
        )
        content()
    }
}

/**
 * Pulls a colour most of the way toward the app background — this is what keeps covers subtle.
 *
 * The artwork contributes a *tint*, never a fill. Blending 70-80% of the way to the OLED base
 * means even a fully saturated cover lands as a deep, desaturated wash (the same read as the
 * auth gate's brand glow) rather than the cover's raw colour.
 */
private fun Color.attenuate(amount: Float): Color {
    val base = AurisBackground
    val towardBase = (0.82f - 0.07f * amount).coerceIn(0.62f, 0.92f)
    return Color(
        red = red + (base.red - red) * towardBase,
        green = green + (base.green - green) * towardBase,
        blue = blue + (base.blue - blue) * towardBase,
        alpha = 1f,
    )
}

private fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)