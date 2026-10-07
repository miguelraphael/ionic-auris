package com.auris.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.ArtworkPalette
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * The unified library grid. One screen, one query, three media types — anime, manga and
 * music rows all come from `library_entries`, so a single [LibraryEntryEntity] list drives
 * the whole grid regardless of pillar.
 *
 * Cards use the 2:3 poster ratio for video/print and swap to 1:1 for music via
 * [com.auris.app.ui.components.MediaGridCard]'s caller-provided layout, matching each
 * pillar's native artwork shape.
 */
@Composable
fun LibraryScreen(
    entries: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 120.dp),
    header: (@Composable () -> Unit)? = null,
) {
    // The gradient tracks whichever entry the user last touched, the same rule the player and
    // detail screens follow. Chosen on tap rather than on "most recent" so the screen reacts to
    // what the user is actually looking at, and so the colour is not a function of data ordering.
    var gradientArtwork by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(entries.size) {
        // Seed with the newest entry so the screen is never flat grey on first open.
        if (gradientArtwork == null) {
            gradientArtwork = entries.maxByOrNull { it.lastActivityAt }?.artworkUrl
        }
    }

    // The shared loader inside rememberArtworkPalette handles the decode, so this is just
    // "track this artwork".
    val palette = rememberArtworkPaletteValue(artworkKey = gradientArtwork)

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.7f,
        // Fades early: a grid of many cards behind a gradient reaching half the viewport tints
        // every one of them.
        fadeEndFraction = 0.38f,
        modifier = modifier,
    ) {
    LazyVerticalGrid(
        // Adaptive so the same composable serves a phone, a foldable and a tablet.
        columns = GridCells.Adaptive(minSize = 116.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
        }

        items(
            items = entries,
            key = { it.id },
        ) { entry ->
            MediaGridCard(
                imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                title = entry.title,
                subtitle = entry.subtitle ?: entry.author,
                progress = entry.progress.takeIf { it > 0f },
                onClick = {
                    gradientArtwork = entry.artworkUrl ?: entry.localArtworkPath
                    onEntryClick(entry)
                },
            )
        }
    }
    }
}
