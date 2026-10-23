package com.auris.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.layout.ContentScale
import com.auris.app.core.model.MediaType
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisAccent

/** How many titles a shelf shows before it becomes an archive. */
private const val SHELF_LIMIT = 6

/**
 * Home.
 *
 * Reads the unified library straight off Room and re-frames it into per-pillar shelves.
 * Because all three media types share one table, the shelves below are three filters over
 * the same snapshot — no per-feature queries.
 *
 * ### Dashboard, not archive
 * Each shelf is capped at [SHELF_LIMIT] and links to its pillar for the rest. The Library tab is
 * the place to search, filter and prune; Home is where the last few things you touched live.
 */
@Composable
fun HomeScreen(
    library: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    /** Opens Settings, which is where extensions are installed. */
    onOpenSettings: () -> Unit,
    /** Opens a pillar's full archive; the "See all" action on a capped shelf. */
    onSeeAll: (MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The library arrives ordered `last_activity_at DESC`, so taking the head of each filter
    // keeps the shelf order meaningful without sorting it again here.
    val inProgress = library.filter { it.progress > 0f && !isComplete(it) }
    val allAnime = library.filter { it.mediaType == MediaType.ANIME }
    val allManga = library.filter { it.mediaType == MediaType.MANGA }
    val allMusic = library.filter { it.mediaType == MediaType.MUSIC }

    Column(
        modifier = modifier
            .fillMaxSize()
            // One vertical scroll for the whole dashboard: the shelves are horizontal
            // carousels, so nested scrolling stays on axis.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            // Bottom padding clears the floating nav pill.
            .padding(bottom = 110.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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

            /**
             * The only way into Settings.
             *
             * Home had no header action, so Settings — and the extension manager inside it,
             * which is how anime sources get installed — was reachable from no top-level
             * destination. Settings opened Extensions and Extensions opened Settings, and
             * neither could be entered. Caught only by running the app on real hardware.
             */
            HomeHeaderIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
            )
        }

        if (inProgress.isEmpty() && library.isEmpty()) {
            EmptyHome()
            return
        }

        // ---- Continue shelf: the cross-pillar "resume" row --------------------
        if (inProgress.isNotEmpty()) {
            Shelf(title = "Continue") {
                items(inProgress.take(SHELF_LIMIT), key = { it.id }) { entry ->
                    ContinueCard(
                        entry = entry,
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        // ---- Per-pillar shelves ----------------------------------------------
        if (allMusic.isNotEmpty()) {
            Shelf(
                title = "Music",
                onSeeAll = if (allMusic.size > SHELF_LIMIT) {
                    { onSeeAll(MediaType.MUSIC) }
                } else {
                    null
                },
            ) {
                items(allMusic.take(SHELF_LIMIT), key = { it.id }) { entry ->
                    MediaGridCard(
                        imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                        title = entry.title,
                        subtitle = entry.author,
                        progress = entry.progress.takeIf { it > 0f },
                        // Width only: the card sizes to its content, so the 2-line title is never
                        // squeezed out by a fixed height.
                        modifier = Modifier.width(150.dp),
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        if (allAnime.isNotEmpty()) {
            Shelf(
                title = "Anime",
                onSeeAll = if (allAnime.size > SHELF_LIMIT) {
                    { onSeeAll(MediaType.ANIME) }
                } else {
                    null
                },
            ) {
                items(allAnime.take(SHELF_LIMIT), key = { it.id }) { entry ->
                    MediaGridCard(
                        imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                        title = entry.title,
                        subtitle = entry.currentUnitTitle ?: entry.subtitle,
                        progress = entry.progress.takeIf { it > 0f },
                        modifier = Modifier.width(120.dp),
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }

        if (allManga.isNotEmpty()) {
            Shelf(
                title = "Manga",
                onSeeAll = if (allManga.size > SHELF_LIMIT) {
                    { onSeeAll(MediaType.MANGA) }
                } else {
                    null
                },
            ) {
                items(allManga.take(SHELF_LIMIT), key = { it.id }) { entry ->
                    MediaGridCard(
                        imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                        title = entry.title,
                        subtitle = entry.currentUnitTitle ?: entry.subtitle,
                        progress = entry.progress.takeIf { it > 0f },
                        modifier = Modifier.width(120.dp),
                        onClick = { onEntryClick(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Shelf(
    title: String,
    onSeeAll: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column {
        SectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = 0.dp),
            action = onSeeAll?.let { callback -> { SeeAllAction(onClick = callback) } },
        )
        LazyRow(
            content = content,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 8.dp),
        )
    }
}

/**
 * The link from a capped shelf to its pillar.
 *
 * Deliberately a text action rather than a card: "See all" is navigation, not a title, and a
 * card at the end of a carousel reads as another item to play.
 */
@Composable
private fun SeeAllAction(onClick: () -> Unit) {
    Text(
        text = "See all",
        style = MaterialTheme.typography.labelLarge,
        color = AurisAccent,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
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

/** A 44dp circular header action, matching the app's other header controls. */
@Composable
private fun HomeHeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AurisSurface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = AurisOnSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
