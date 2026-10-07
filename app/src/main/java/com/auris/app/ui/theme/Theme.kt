package com.auris.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Auris is dark-only by design: the dynamic artwork gradient is authored to fade into a
 * deep background, and a light variant would wash out the extracted colours.
 * [darkTheme] is accepted so previews can force the palette, not to support a light mode.
 */
private val AurisColorScheme = darkColorScheme(
    primary = AurisBlue,
    onPrimary = Color.White,
    primaryContainer = AurisIndigo,
    onPrimaryContainer = Color.White,

    secondary = AurisViolet,
    onSecondary = Color.White,

    tertiary = AurisOrange,
    onTertiary = Color.White,

    background = AurisBackground,
    onBackground = AurisOnSurface,

    surface = AurisSurface,
    onSurface = AurisOnSurface,
    surfaceVariant = AurisTrack,
    onSurfaceVariant = AurisOnSurfaceVariant,

    surfaceTint = AurisBlue,

    outline = AurisOutline,
    outlineVariant = AurisTrack,

    error = AurisRose,
    onError = Color.White,
)

@Composable
fun AurisTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Edge-to-edge; the artwork gradient is drawn behind the system bars.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(AurisShapesProvider provides AurisShapes) {
        MaterialTheme(
            colorScheme = AurisColorScheme,
            typography = AurisTypography,
            shapes = AurisShapesProvider.current,
            content = content,
        )
    }
}