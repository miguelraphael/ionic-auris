package com.auris.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * A single colour extracted from the active media artwork (album cover, manga cover,
 * anime poster). Supplied per-screen so the gradient always tracks the content being
 * viewed rather than the last thing the player touched.
 */
data class ArtworkPalette(
    /** Dominant / vibrant swatch. */
    val primary: Color = AurisBlue,
    /** Muted companion for secondary fills. */
    val secondary: Color = AurisIndigo,
    /** Darkest swatch, used for the fade target. */
    val darkest: Color = AurisBackground,
    /** Lightest swatch, used for artwork scrims and on-colour text. */
    val light: Color = AurisOnSurface,
    val isGenerated: Boolean = false,
) {
    companion object {
        /** Used before Palette extraction completes, so gradients never flash grey. */
        val Unspecified = ArtworkPalette()

        val Fallback = ArtworkPalette(
            primary = AurisBlue,
            secondary = AurisIndigo,
            darkest = AurisBackground,
            light = AurisOnSurface,
        )
    }
}