package com.auris.app.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisRose
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * The unified library grid, and the place the archive is actually managed.
 *
 * One screen, one query, three media types — anime, manga and music rows all come from
 * `library_entries`, so a single [LibraryEntryEntity] list drives the whole grid regardless of
 * pillar. Cards use the 2:3 poster ratio for video/print (music rows carry their square art
 * inside the same frame), matching each pillar's native artwork shape.
 *
 * ### Why management lives here rather than on Home
 * Home is a dashboard: six recent titles per pillar. Once a library grows past that, finding one
 * title means search, narrowing by type, choosing an order and — because saved rows accumulate
 * from browsing — deleting in bulk. All four exist only on this screen.
 *
 * ### Selection is local, deletion is not
 * The selection is a screen concern and resets with it. Removal goes through `onDelete`, which
 * reaches [LibraryViewModel] and the repository's soft delete, so the tombstone still syncs.
 */
@Composable
fun LibraryScreen(
    entries: List<LibraryEntryEntity>,
    onEntryClick: (LibraryEntryEntity) -> Unit,
    onDelete: (List<LibraryEntryEntity>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 20.dp,
        end = 20.dp,
        // Clears the status bar; the grid used to start at y = 0, under the clock.
        top = 56.dp,
        // Clears the floating nav pill.
        bottom = 120.dp,
    ),
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

    // Saver-friendly primitives: the query, the filter's name and the sort direction all survive
    // process death. Collection state is not saveable, so it lives in `remember`.
    var query by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf<String?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val filter = filterName?.let { name -> MediaType.entries.firstOrNull { it.name == name } }
    val visible = remember(entries, query, filter, newestFirst) {
        entries
            .asSequence()
            .filter { entry -> filter == null || entry.mediaType == filter }
            .filter { entry ->
                query.isBlank() ||
                    entry.title.contains(query, ignoreCase = true) ||
                    entry.subtitle?.contains(query, ignoreCase = true) == true ||
                    entry.author?.contains(query, ignoreCase = true) == true
            }
            .let { rows ->
                if (newestFirst) rows else rows.sortedBy { it.title.lowercase() }
            }
            .toList()
    }

    val selectedEntries = entries.filter { it.id in selectedIds }
    val selectionActive = selectedIds.isNotEmpty()

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
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.displayLarge,
                        color = AurisOnSurface,
                    )
                    Text(
                        text = countLabel(total = entries.size, shown = visible.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurisOnSurfaceVariant,
                    )
                }
            }

            if (header != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { header() }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "controls") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LibrarySearchField(
                            query = query,
                            onQueryChange = { query = it },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        SortPill(
                            newestFirst = newestFirst,
                            onToggle = { newestFirst = !newestFirst },
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        FilterPill(
                            label = "All",
                            selected = filter == null,
                            onClick = { filterName = null },
                        )
                        MediaType.entries.forEach { type ->
                            FilterPill(
                                label = type.name.lowercase().replaceFirstChar(Char::uppercase),
                                selected = filter == type,
                                onClick = {
                                    filterName = if (filter == type) null else type.name
                                },
                            )
                        }
                    }
                }
            }

            if (selectionActive) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "selection") {
                    SelectionBar(
                        count = selectedEntries.size,
                        onRemove = { confirmDelete = true },
                        onCancel = { selectedIds = emptySet() },
                    )
                }
            }

            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        text = if (entries.isEmpty()) {
                            "Nothing saved yet. Add something from a pillar to see it here."
                        } else {
                            "No titles match. Try a different search or filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurisOnSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            items(
                items = visible,
                key = { it.id },
            ) { entry ->
                MediaGridCard(
                    imageUrl = entry.artworkUrl ?: entry.localArtworkPath,
                    title = entry.title,
                    subtitle = entry.subtitle ?: entry.author,
                    progress = entry.progress.takeIf { it > 0f },
                    selected = entry.id in selectedIds,
                    onLongClick = {
                        // Long-press is the only way into selection, so it must also work when
                        // selection is already on (toggling the pressed card off).
                        selectedIds = if (entry.id in selectedIds) {
                            selectedIds - entry.id
                        } else {
                            selectedIds + entry.id
                        }
                    },
                    onClick = {
                        if (selectionActive) {
                            selectedIds = if (entry.id in selectedIds) {
                                selectedIds - entry.id
                            } else {
                                selectedIds + entry.id
                            }
                        } else {
                            gradientArtwork = entry.artworkUrl ?: entry.localArtworkPath
                            onEntryClick(entry)
                        }
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = AurisBackground,
            title = {
                Text(
                    text = if (selectedEntries.size == 1) {
                        "Remove this title?"
                    } else {
                        "Remove ${selectedEntries.size} titles?"
                    },
                    color = AurisOnSurface,
                )
            },
            text = {
                Text(
                    text = if (selectedEntries.size == 1) {
                        "It will be removed from your library and from your other devices."
                    } else {
                        "They will be removed from your library and from your other devices."
                    },
                    color = AurisOnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(selectedEntries)
                        selectedIds = emptySet()
                        confirmDelete = false
                    },
                ) {
                    Text("Remove", color = AurisRose)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel", color = AurisOnSurfaceVariant)
                }
            },
        )
    }
}

private fun countLabel(total: Int, shown: Int): String = when {
    total == 0 -> "Nothing saved yet"
    shown != total -> "$shown of $total titles"
    total == 1 -> "1 title"
    else -> "$total titles"
}

/**
 * Search over title, subtitle and author.
 *
 * A `BasicTextField` inside a surface rather than an `OutlinedTextField` so it reads as the same
 * pill as the app's other controls; the M3 outlined field's 56dp box and border clash with the
 * floating nav and shelf headers.
 */
@Composable
private fun LibrarySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisSurface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = AurisOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))

            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search your library",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AurisOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = AurisOnSurface),
                    cursorBrush = SolidColor(AurisAccent),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = AurisOnSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onQueryChange("") },
                )
            }
        }
    }
}

/** Toggles between newest-first (the Room order) and title A–Z. */
@Composable
private fun SortPill(
    newestFirst: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = AurisSurface,
        modifier = Modifier
            .height(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SwapVert,
                contentDescription = null,
                tint = AurisOnSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (newestFirst) "Recent" else "A–Z",
                style = MaterialTheme.typography.labelLarge,
                color = AurisOnSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) AurisAccent.copy(alpha = 0.18f) else AurisSurface,
        border = if (selected) {
            BorderStroke(1.dp, AurisAccent.copy(alpha = 0.45f))
        } else {
            null
        },
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AurisAccent else AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The multi-select action bar.
 *
 * Appears between the filters and the grid rather than replacing the header: keeping the count
 * in place means the bar does not jump when the first card is selected.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisAccent.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 1) "1 selected" else "$count selected",
                style = MaterialTheme.typography.titleSmall,
                color = AurisAccent,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRemove) {
                Text("Remove", color = AurisRose)
            }
            TextButton(onClick = onCancel) {
                Text("Cancel", color = AurisOnSurfaceVariant)
            }
        }
    }
}
