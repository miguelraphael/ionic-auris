package com.auris.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared corner scale: 24dp surfaces, 12dp compact elements, fully rounded transport/pills. */
object AurisRadii {
    /** Grid thumbnails, episode cards, cover art. */
    val artwork: Dp = 24.dp
    val artworkLarge: Dp = 24.dp
    val artworkSmall: Dp = 12.dp

    /** Floating nav pill: 50dp tall, fully rounded. */
    val navPill: Dp = 24.dp

    /** Chips, badges, small control clusters. */
    val chip: Dp = 12.dp
    val button: Dp = 24.dp
    val sheet: Dp = 24.dp
}

/** Material3 shape scale for every card, sheet and dialog in the app. */
val AurisShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(AurisRadii.button),
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