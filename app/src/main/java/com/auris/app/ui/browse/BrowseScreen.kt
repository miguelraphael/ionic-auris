package com.auris.app.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant

/**
 * Shared browse surface for the three catalogue tabs.
 *
 * Anime, manga and music differ only in artwork ratio and labels, so they share one
 * composable rather than three near-identical files. Once source-backed catalogue browsing
 * lands, this is where the extension/InnerTube query result replaces the Room-derived list.
 */
@Composable
fun BrowseScreen(
    mediaType: MediaType,
    entries: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp)) {
            Text(
                text = mediaType.displayName(),
                style = MaterialTheme.typography.displayLarge,
                color = AurisOnSurface,
            )
            Text(
                text = "${entries.size} in your library",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )
        }

        SectionHeader(
            title = "In progress",
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        if (entries.isEmpty()) {
            EmptyCatalogue(mediaType, Modifier.weight(1f))
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 116.dp),
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            // Clears the floating nav pill.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                MediaGridCard(
                    imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                    title = entry.title,
                    subtitle = entry.currentUnitTitle ?: entry.author,
                    progress = entry.progress.takeIf { it > 0f },
                    onClick = { onEntryClick(entry) },
                )
            }
        }
    }
}

@Composable
private fun EmptyCatalogue(mediaType: MediaType, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No ${mediaType.displayName().lowercase()} yet",
                style = MaterialTheme.typography.titleLarge,
                color = AurisOnSurface,
            )
            Text(
                text = "Extensions and catalogues appear here once configured.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 48.dp, end = 48.dp),
            )
        }
    }
}

private fun MediaType.displayName(): String = when (this) {
    MediaType.ANIME -> "Anime"
    MediaType.MANGA -> "Manga"
    MediaType.MUSIC -> "Music"
}
