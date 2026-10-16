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

// ---- Brand gradient (product spec) ----
// A separate sweep from [LogoGradient]: the logo's own hues must stay tied to the
// logo asset, whereas these four are the product palette drawn on chrome (glows,
// accent strokes, highlights) and are free to be more saturated.

/** Electric Cyan. */
val BrandCyan = Color(0xFF22D3EE)

/** Royal Indigo. */
val BrandIndigo = Color(0xFF4F46E5)

/** Neon Purple. */
val BrandPurple = Color(0xFFA855F7)

/** Sunset Orange. */
val BrandOrange = Color(0xFFFB923C)

/** The brand sweep, cyan to orange. UI chrome only — never tint the logo with it. */
val BrandGradient = listOf(BrandCyan, BrandIndigo, BrandPurple, BrandOrange)

// ---- Reference UI surfaces (sampled from UI/music-player-UI.jpg at 1080x2400) ----

/**
 * Root app background: true OLED black, slightly lifted off pure #000 so elevation
 * (surfaces, glass) still reads as a layer rather than a hole. The artwork gradient
 * fades into this.
 */
val AurisBackground = Color(0xFF0B0B0E)

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