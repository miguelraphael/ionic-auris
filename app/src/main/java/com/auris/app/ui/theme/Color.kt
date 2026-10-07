package com.auris.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette derived from the Auris logo: a cyan-to-blue left stroke, a magenta-to-orange
 * right stroke, and a deep indigo ribbon through the middle.
 *
 * Sampled dominant hues from `Logos/AURIS logo on transparent background.png`:
 * `#0078F0` blue, `#F03048` magenta-red, `#F07818` orange, `#300078` indigo.
 */
val AurisBlue = Color(0xFF0A84FF)
val AurisBlueBright = Color(0xFF3DA5FF)
val AurisIndigo = Color(0xFF3B1E8C)
val AurisViolet = Color(0xFF7B2FD4)
val AurisMagenta = Color(0xFFC026D3)
val AurisRose = Color(0xFFE8305A)
val AurisOrange = Color(0xFFF07818)
val AurisAmber = Color(0xFFFFA82B)

// ---- Reference UI surfaces (sampled from UI/music-player-UI.jpg at 1080x2400) ----

/** Bottom app background below the artwork gradient fade. */
val AurisBackground = Color(0xFF161B24)

/** Floating nav pill surface. */
val AurisSurface = Color(0xFF2C303C)

/** Progress bar unfilled track. */
val AurisTrack = Color(0xFF252A2E)

/** Seek / volume bar fill. */
val AurisAccent = Color(0xFF86B4FF)

val AurisOnSurface = Color(0xFFE8EAF0)
val AurisOnSurfaceVariant = Color(0xFF9AA0AE)
val AurisOutline = Color(0xFF3A404D)

/** The logo gradient, used for splash marks, selected-nav pills and focus rings. */
val LogoGradient = listOf(AurisBlue, AurisViolet, AurisMagenta, AurisOrange)

/**
 * Fallback gradient applied before the Palette library has resolved colours for the
 * active artwork. Matches the olive cast of the reference screenshot's top edge.
 */
val FallbackArtworkGradient = listOf(Color(0xFF3A3B24), AurisBackground)