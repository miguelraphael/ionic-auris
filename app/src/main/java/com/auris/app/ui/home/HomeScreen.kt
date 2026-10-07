package com.auris.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant

/**
 * Home.
 *
 * Reads the unified library straight off Room and re-frames it into per-pillar shelves.
 * Because all three media types share one table, the shelves below are three filters over
 * the same snapshot — no per-feature queries.
 */
@Composable
fun HomeScreen(
    library: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val inProgress = library.filter { it.progress > 0f && !isComplete(it) }
    val recentAnime = library.filter { it.mediaType == MediaType.ANIME }
    val recentManga = library.filter { it.mediaType == MediaType.MANGA }
    val recentMusic = library.filter { it.mediaType == MediaType.MUSIC }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            Modifier.padding(top = 56.dp, bottom = 4.dp),
        ) {
            Text(
                text = "Auris",
                style = MaterialTheme.typography.displayLarge,
                color = AurisOnSurface,
            )
            Text(
                text = "Anime, manga and music in one place",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )
        }

        if (inProgress.isEmpty() && library.isEmpty()) {
            EmptyHome()
            return
        }

        // ---- Continue shelf: the cross-pillar "resume" row --------------------
        if (inProgress.isNotEmpty()) {
            Shelf(title = "Continue") {
                items(inProgress, key = { it.id }) { entry ->
                    ContinueCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        // ---- Per-pillar shelves ----------------------------------------------
        if (recentMusic.isNotEmpty()) {
            Shelf(title = "Music") {
                items(recentMusic, key = { it.id }) { entry ->
                    MediaGridCard(
                        imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                        title = entry.title,
                        subtitle = entry.author,
                        progress = entry.progress.takeIf { it > 0f },
                        modifier = Modifier.size(width = 150.dp, height = 210.dp),
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        if (recentAnime.isNotEmpty()) {
            Shelf(title = "Anime") {
                items(recentAnime, key = { it.id }) { entry ->
                    MediaGridCard(
                        imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                        title = entry.title,
                        subtitle = entry.currentUnitTitle ?: entry.subtitle,
                        progress = entry.progress.takeIf { it > 0f },
                        modifier = Modifier.size(width = 120.dp, height = 200.dp),
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        if (recentManga.isNotEmpty()) {
            Shelf(title = "Manga") {
                items(recentManga, key = { it.id }) { entry ->
                    MediaGridCard(
                        imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                        title = entry.title,
                        subtitle = entry.currentUnitTitle ?: entry.subtitle,
                        progress = entry.progress.takeIf { it > 0f },
                        modifier = Modifier.size(width = 120.dp, height = 200.dp),
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        // Bottom padding clears the floating nav pill.
        Column(Modifier.padding(bottom = 110.dp)) {}
    }
}

@Composable
private fun Shelf(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column {
        SectionHeader(title = title, modifier = Modifier.padding(horizontal = 0.dp))
        LazyRow(
            content = content,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        )
    }
}

/** Wide resume card with the progress bar pinned under the art. */
@Composable
private fun ContinueCard(
    entry: LibraryEntryEntity,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.size(width = 260.dp, height = 170.dp),
    ) {
        MediaArtwork(
            imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
            contentDescription = entry.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(),
            scrim = 0.35f,
        )
        Text(
            text = entry.title,
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
            maxLines = 1,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = entry.currentUnitTitle ?: "${(entry.progress * 100).toInt()}% complete",
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun isComplete(entry: LibraryEntryEntity): Boolean =
    entry.progress >= 1f || entry.totalUnits > 0 &&
        entry.currentUnitIndex >= entry.totalUnits

@Composable
private fun EmptyHome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 80.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Nothing here yet",
            style = MaterialTheme.typography.titleLarge,
            color = AurisOnSurface,
        )
        Text(
            text = "Add an extension or search the catalogues to start building your library.",
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp),
        )
    }
}