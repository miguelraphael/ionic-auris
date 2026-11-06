package com.auris.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.components.SkeletonShelf
import com.auris.app.ui.components.StateEmpty
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack

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
    /** The first Room emission has not arrived yet. Distinguishable from a genuinely empty library. */
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // The library arrives ordered `last_activity_at DESC`, so taking the head of each filter
    // keeps the shelf order meaningful without sorting it again here.
    val inProgress = remember(library) { library.filter { it.progress > 0f && !isComplete(it) } }
    val allAnime = remember(library) { library.filter { it.mediaType == MediaType.ANIME } }
    val allManga = remember(library) { library.filter { it.mediaType == MediaType.MANGA } }
    val allMusic = remember(library) { library.filter { it.mediaType == MediaType.MUSIC } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Bottom padding clears the floating nav pill.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 110.dp),
    ) {
        item(key = "header", contentType = "header") {
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
        }

        if (isLoading && library.isEmpty()) {
            // A skeleton, not a spinner: the dashboard has a known shape, and showing that
            // shape before the data lands avoids the empty-then-pop reflow a spinner causes.
            items(2, key = { "loading-$it" }, contentType = { "loading" }) {
                SkeletonShelf()
            }
            return@LazyColumn
        }

        if (library.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                StateEmpty(
                    title = "Your library is empty",
                    description = "Browse anime or manga, or search for music. Everything you open " +
                        "or save lands here, ready to resume.",
                    actionLabel = "Browse anime",
                    onAction = { onSeeAll(MediaType.ANIME) },
                )
            }
            return@LazyColumn
        }

        // ---- Continue shelf: the cross-pillar "resume" row --------------------
        if (inProgress.isNotEmpty()) {
            item(key = "continue", contentType = "shelf") {
                Shelf(title = "Continue") {
                    items(inProgress.take(SHELF_LIMIT), key = { it.id }) { entry ->
                        ContinueCard(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                        )
                    }
                }
            }
        }

        // ---- Per-pillar shelves ----------------------------------------------
        if (allMusic.isNotEmpty()) {
            item(key = "music", contentType = "shelf") {
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
        }

        if (allAnime.isNotEmpty()) {
            item(key = "anime", contentType = "shelf") {
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
        }

        if (allManga.isNotEmpty()) {
            item(key = "manga", contentType = "shelf") {
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

/**
 * Resume card showing the complete 2:3 vertical poster art without cropping,
 * alongside title, chapter/episode label, and interactive resume touch target.
 */
@Composable
private fun ContinueCard(
    entry: LibraryEntryEntity,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = AurisSurface,
        modifier = Modifier.size(width = 280.dp, height = 126.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                MediaArtwork(
                    imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                    contentDescription = entry.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AurisAccent.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = entry.mediaType.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = AurisAccent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = AurisOnSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = entry.currentUnitTitle ?: if (entry.progress > 0f) "${(entry.progress * 100).toInt()}% complete" else "In progress",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                LinearProgressIndicator(
                    progress = { entry.progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = AurisAccent,
                    trackColor = AurisTrack,
                )
            }
        }
    }
}

private fun isComplete(entry: LibraryEntryEntity): Boolean =
    entry.progress >= 1f || entry.totalUnits > 0 &&
        entry.currentUnitIndex >= entry.totalUnits

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
