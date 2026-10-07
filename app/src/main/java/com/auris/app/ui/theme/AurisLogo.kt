package com.auris.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auris.app.R

/**
 * The Auris logo, choosing a variant that stays legible on whatever it is drawn over.
 *
 * ### Why a runtime choice rather than one asset
 * The app is a dark surface, but not entirely: manga pages are often white, artwork gradients
 * can be bright, and the reader/player overlays float over arbitrary imagery. A single logo
 * asset would be invisible or glaring in half those cases. [AurisLogo] picks between a
 * light-ink and a dark-ink build from the measured luminance of the backdrop.
 *
 * The two assets are generated from `Logos/Auris-black.png` and `Logos/Auris-white.png`, both of
 * which are *opaque* (logo on a solid backdrop). Their alpha channels are synthesised from
 * luminance at build time, so both variants are true transparent PNGs with flat ink and no
 * fringe.
 *
 * ### Contrast
 * Selection uses [luminance], the same relative-luminance measure the WCAG contrast formula
 * uses, against the 0.5 threshold. That is the same crossover point at which either ink clears
 * 4.5:1 against the backdrop, so the choice is the contrast requirement rather than an
 * aesthetic preference.
 */
@Composable
fun AurisLogo(
    modifier: Modifier = Modifier,
    /** Explicit backdrop. Defaults to whatever [LocalLogoBackdrop] provides. */
    background: Color = LocalLogoBackdrop.current,
    size: Dp = 40.dp,
    /** The full lockup including the AURIS wordmark. Off for small sizes. */
    includeWordmark: Boolean = false,
    alpha: Float = 1f,
) {
    val useDarkInk = background.luminance() > LUMINANCE_THRESHOLD

    val res = when {
        includeWordmark && useDarkInk -> R.drawable.logo_full_dark
        includeWordmark -> R.drawable.logo_full_light
        useDarkInk -> R.drawable.logo_mark_dark
        else -> R.drawable.logo_mark_light
    }

    Image(
        painter = painterResource(res),
        contentDescription = null,
        // The assets are already flat single-colour ink, so a tint is not applied — that would
        // multiply against the existing colour. Opacity is, via the alpha layer, which is how
        // the reader watermark is dimmed without dimming the artwork behind it.
        alpha = alpha,
        modifier = modifier.size(size),
    )
}

/**
 * Logo drawn centred on an explicit backdrop.
 *
 * Provided so a logo placed over an unknown surface (an artwork gradient, a manga page) is
 * measured against the surface it actually sits on rather than the theme's nominal background.
 */
@Composable
fun AurisLogoOn(
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    includeWordmark: Boolean = false,
) {
    Box(modifier = modifier.background(background)) {
        CompositionLocalProvider(LocalLogoBackdrop provides background) {
            AurisLogo(
                background = background,
                size = size,
                includeWordmark = includeWordmark,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * The backdrop the logo should contrast against.
 *
 * Overridable per subtree so a screen drawing the logo over imagery does not have to thread the
 * colour through manually. Defaults to the app background, which is what every in-app placement
 * sits on unless it says otherwise.
 */
val LocalLogoBackdrop = compositionLocalOf { AurisBackground }

/**
 * Crossover luminance for ink selection.
 *
 * Below this, light ink; above, dark ink. 0.5 is also the point where either choice clears
 * WCAG AA (4.5:1) against the opposite ink, so the two criteria coincide.
 */
private const val LUMINANCE_THRESHOLD = 0.5f