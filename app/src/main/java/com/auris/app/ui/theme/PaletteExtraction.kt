package com.auris.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import java.util.concurrent.Executors

/**
 * Single-threaded executor: Palette quantization is CPU-bound, and one background thread is
 * plenty since only the foreground screen's artwork is ever being extracted.
 */
private val paletteExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "auris-palette").apply { isDaemon = true }
}

/**
 * Extracts dominant swatches from [artworkBitmap] off the main thread.
 *
 * ### Cost
 * Quantizing a full-size cover is expensive enough to drop frames, so:
 *  - work happens on [paletteExecutor], never the main thread;
 *  - the bitmap is downscaled to a 96x96 area before quantizing;
 *  - the returned state is only swapped in once, so callers see a single recomposition
 *    rather than one per swatch.
 *
 * Callers should cache by artwork key (see [rememberArtworkPalette]); this function
 * intentionally holds no cache of its own.
 */
@Composable
fun rememberArtworkPalette(
    artworkKey: String?,
    artworkBitmap: Bitmap?,
    onBitmap: suspend (String) -> Bitmap? = { null },
): State<ArtworkPalette> {
    val state = remember(artworkKey) { mutableStateOf(ArtworkPalette.Unspecified) }

    LaunchedEffect(artworkKey, artworkBitmap) {
        if (artworkKey == null) {
            state.value = ArtworkPalette.Unspecified
            return@LaunchedEffect
        }
        val bitmap = artworkBitmap ?: runCatching { onBitmap(artworkKey) }.getOrNull()
        if (bitmap == null) {
            state.value = ArtworkPalette.Unspecified
            return@LaunchedEffect
        }
        state.value = paletteExecutor.submit<ArtworkPalette> { extract(bitmap) }.get()
    }

    return state
}

@Composable
fun rememberArtworkPaletteValue(
    artworkKey: String?,
    artworkBitmap: Bitmap? = null,
    onBitmap: suspend (String) -> Bitmap? = { null },
): ArtworkPalette = rememberArtworkPalette(artworkKey, artworkBitmap, onBitmap).value

/**
 * Maps a Palette result onto our four-role scheme.
 *
 * Falls back in a deliberate order: a vibrant swatch can be missing for monochrome or very
 * dark covers, so each role degrades toward the next candidate and finally to the logo
 * palette. `defaultColor` is used for every target Palette didn't satisfy, which is why the
 * result never contains a fully transparent colour.
 */
private fun extract(bitmap: Bitmap): ArtworkPalette {
    val palette = runCatching {
        Palette.from(bitmap.toPaletteSource())
            .clearFilters()
            .maximumColorCount(24)
            .resizeBitmapArea(SAMPLE_AREA)
            .generate()
    }.getOrNull() ?: return ArtworkPalette.Fallback

    val vibrant = palette.vibrantSwatch?.rgb
    val lightVibrant = palette.lightVibrantSwatch?.rgb
    val darkVibrant = palette.darkVibrantSwatch?.rgb
    val muted = palette.mutedSwatch?.rgb
    val dominant = palette.dominantSwatch?.rgb

    return ArtworkPalette(
        primary = (vibrant ?: dominant)?.toColor() ?: ArtworkPalette.Fallback.primary,
        secondary = (darkVibrant ?: muted)?.toColor() ?: ArtworkPalette.Fallback.secondary,
        darkest = (darkVibrant ?: dominant)?.toColor() ?: AurisBackground,
        light = (lightVibrant ?: vibrant)?.toColor() ?: AurisOnSurface,
        isGenerated = true,
    )
}

/** 96x96 — Palette's own default sample area, stated explicitly to document the tradeoff. */
private const val SAMPLE_AREA = 96 * 96

/**
 * Palette requires a non-mutable ARGB_8888 bitmap. Conversions can hand us hardware
 * bitmaps (RGBA_F16, HARDWARE), which Palette throws on.
 */
fun Bitmap.toPaletteSource(): Bitmap = when {
    config == Bitmap.Config.ARGB_8888 && !isMutable -> this
    else -> copy(Bitmap.Config.ARGB_8888, false)
}

private fun Int.toColor(): Color = Color(this)