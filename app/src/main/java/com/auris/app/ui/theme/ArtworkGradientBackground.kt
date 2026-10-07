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
 * The global background: the active artwork's dominant colour fading into the dark app
 * background, per the aesthetic rules.
 *
 * Three behaviours matter for the reference look:
 *  1. The gradient starts behind the top of the content and reaches full [AurisBackground]
 *     by roughly 45-50% of the height, so the lower half is flat dark.
 *  2. The colour is attenuated toward the background as well as desaturated, otherwise
 *     bright covers (the olive album art here) overpower the content.
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                val top = palette.primary.attenuate(intensity)
                val mid = palette.secondary.attenuate(intensity * 0.7f)
                val endY = size.height * fadeEndFraction

                drawRect(
                    brush = Brush.verticalGradient(
                        0f to top,
                        0.35f to mix(top, mid, 0.5f),
                        1f to AurisBackground,
                        startY = 0f,
                        endY = endY,
                    ),
                    size = size,
                )

                // A faint radial bloom behind the artwork keeps large covers from
                // producing a flat band of colour.
                drawRect(
                    brush = Brush.radialGradient(
                        0f to top.copy(alpha = 0.35f * intensity),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height * 0.16f),
                        radius = size.minDimension * 0.9f,
                    ),
                )
            },
    ) {
        content()
    }
}

/** Pulls a colour toward the app background — this is what keeps covers subtle. */
private fun Color.attenuate(amount: Float): Color {
    val base = AurisBackground
    return Color(
        red = red + (base.red - red) * (1f - amount.coerceIn(0f, 1f)) * 0.55f,
        green = green + (base.green - green) * (1f - amount.coerceIn(0f, 1f)) * 0.55f,
        blue = blue + (base.blue - blue) * (1f - amount.coerceIn(0f, 1f)) * 0.55f,
        alpha = 1f,
    )
}

private fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)