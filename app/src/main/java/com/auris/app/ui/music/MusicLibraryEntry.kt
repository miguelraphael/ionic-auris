package com.auris.app.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.theme.AurisOnSurfaceVariant

/**
 * Saved music.
 *
 * Sits above the live browse screen rather than replacing it: this is what the user has *added*,
 * and browse is what is available. Both are reachable from the Music pill so neither is a
 * dead end.
 */
@Composable
fun MusicLibraryEntry(
    entries: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    onOpenBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Full-width header carrying the only call to action on an otherwise empty screen.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp)) {
                SectionHeader(title = "Your music")
                if (entries.isEmpty()) {
                    Text(
                        text = "Nothing saved yet. Search YouTube Music to add tracks.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurisOnSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    BrowseButton(onOpenBrowse)
                }
            }
        }

        items(entries, key = { it.id }) { entry ->
            MediaGridCard(
                imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                title = entry.title,
                subtitle = entry.subtitle ?: entry.author,
                progress = entry.progress.takeIf { it > 0f },
                onClick = { onEntryClick(entry) },
            )
        }
    }
}

@Composable
private fun BrowseButton(onOpenBrowse: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = com.auris.app.ui.theme.AurisAccent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onOpenBrowse),
    ) {
        Text(
            text = "Browse YouTube Music",
            style = MaterialTheme.typography.labelLarge,
            color = com.auris.app.ui.theme.AurisBackground,
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 20.dp),
        )
    }
}
