package com.auris.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii used across the app.
 *
 * The artwork value (28dp) comes from the reference screenshot: the 972px album art's
 * corner radius measured ~80px at 2.625x density ≈ 30dp, snapped to this scale's step.
 */
object AurisRadii {
    /** Grid thumbnails, episode cards, cover art. */
    val artwork: Dp = 28.dp
    val artworkLarge: Dp = 32.dp
    val artworkSmall: Dp = 18.dp

    /** Floating nav pill: 50dp tall, fully rounded. */
    val navPill: Dp = 28.dp

    /** Chips, badges, small control clusters. */
    val chip: Dp = 14.dp
    val button: Dp = 20.dp
    val sheet: Dp = 32.dp
}

/** Material3 shape scale for every card, sheet and dialog in the app. */
val AurisShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(AurisRadii.chip),
    small = RoundedCornerShape(AurisRadii.artworkSmall),
    medium = RoundedCornerShape(AurisRadii.artwork),
    large = RoundedCornerShape(AurisRadii.artworkLarge),
    extraLarge = RoundedCornerShape(AurisRadii.sheet),
)

val AurisShapesProvider = staticCompositionLocalOf { AurisShapes }

/** Shorthand shapes for art surfaces. */
val ArtworkShape = RoundedCornerShape(AurisRadii.artwork)
val ArtworkShapeLarge = RoundedCornerShape(AurisRadii.artworkLarge)
val ArtworkShapeSmall = RoundedCornerShape(AurisRadii.artworkSmall)
val NavPillShape = RoundedCornerShape(percent = 50)