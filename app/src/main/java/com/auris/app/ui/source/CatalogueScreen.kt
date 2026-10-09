package com.auris.app.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.auris.app.source.SourceItem
import com.auris.app.ui.components.MediaGridCard
import com.auris.app.ui.components.SectionHeader
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * A source's catalogue: Popular, Latest and Search, as a grid.
 *
 * ### Why the gradient follows the first item
 * Every other screen in the app tints from the most recently viewed artwork. Doing the same here
 * means browsing a catalogue and then opening a title is one continuous gesture rather than a
 * palette jump.
 */
@Composable
fun CatalogueScreen(
    state: CatalogueUiState,
    onModeChange: (CatalogueUiState.Mode) -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onItemClick: (SourceItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberArtworkPaletteValue(artworkKey = state.items.firstOrNull()?.thumbnailUrl)

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.8f,
        fadeEndFraction = 0.4f,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize()) {
            CatalogueHeader(state)

            ModeTabs(selected = state.mode, onSelect = onModeChange)

            CatalogueSearchField(
                query = state.query,
                onQueryChange = onQueryChange,
            )

            when {
                state.error != null -> CatalogueError(state.error, onRetry)
                state.isLoading -> LoadingGrid()
                state.isEmpty -> EmptyCatalogue(state)
                else -> CatalogueGrid(state, onItemClick)
            }
        }
    }
}

// ---- Header ------------------------------------------------------------------

@Composable
private fun CatalogueHeader(state: CatalogueUiState) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp)) {
        Text(
            text = state.sourceName.ifBlank { "Catalogue" },
            style = MaterialTheme.typography.displayLarge,
            color = AurisOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.sourceLanguage.isNotBlank()) {
            Text(
                text = state.sourceLanguage,
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeTabs(
    selected: CatalogueUiState.Mode,
    onSelect: (CatalogueUiState.Mode) -> Unit,
) {
    val modes = listOf(
        CatalogueUiState.Mode.POPULAR to "Popular",
        CatalogueUiState.Mode.LATEST to "Latest",
    )

    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        modes.forEach { (mode, label) ->
            val isSelected = selected == mode || (mode == CatalogueUiState.Mode.POPULAR && selected == CatalogueUiState.Mode.SEARCH)
            Surface(
                shape = RoundedCornerShape(AurisRadii.chip),
                color = if (isSelected) AurisAccent else AurisSurface,
                modifier = Modifier.clickable { onSelect(mode) },
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) AurisBackground else AurisOnSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CatalogueSearchField(query: String, onQueryChange: (String) -> Unit) {
    var field by remember(query) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(query)) }

    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
                if (field.text.isEmpty()) {
                    Text(
                        text = "Search this source",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AurisOnSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = field,
                    onValueChange = { value ->
                        field = value
                        onQueryChange(value.text)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AurisOnSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    cursorBrush = SolidColor(AurisAccent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (field.text.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear",
                    tint = AurisOnSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable {
                            field = androidx.compose.ui.text.input.TextFieldValue("")
                            onQueryChange("")
                        },
                )
            }
        }
    }
}

// ---- Content -----------------------------------------------------------------

@Composable
private fun CatalogueGrid(state: CatalogueUiState, onItemClick: (SourceItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            SectionHeader(title = state.title)
        }

        items(state.items, key = { it.url }) { item ->
            MediaGridCard(
                imageUrl = item.thumbnailUrl,
                title = item.title,
                subtitle = item.author ?: item.year?.toString(),
                progress = null,
                onClick = { onItemClick(item) },
            )
        }

        if (state.hasNextPage) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                // Pagination is deliberately absent: every screen in this app loads page 1 only,
                // and an unbounded grid is a worse failure than a missing page. Flagged rather
                // than silently truncating.
                Text(
                    text = "More results are not loaded on this screen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurisOnSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingGrid() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(6) {
            ShimmerCard()
        }
    }
}

/** A placeholder in the grid's own geometry, so laying out real items does not shift the list. */
@Composable
private fun ShimmerCard() {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp > 400) 210.dp else 170.dp)
                .clip(RoundedCornerShape(AurisRadii.artwork))
                .background(AurisSurface),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .height(14.dp)
                .clip(RoundedCornerShape(AurisRadii.chip))
                .background(AurisSurface),
        )
    }
}

@Composable
private fun CatalogueError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Could not load this catalogue",
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = AurisOnSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(AurisRadii.chip),
            color = AurisAccent,
            modifier = Modifier.clickable(onClick = onRetry),
        ) {
            Text(
                text = "Retry",
                style = MaterialTheme.typography.labelLarge,
                color = AurisBackground,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun EmptyCatalogue(state: CatalogueUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (state.isSearching) "No results for \"${state.query}\"" else "Nothing here",
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "This source returned no items.",
            style = MaterialTheme.typography.bodySmall,
            color = AurisOnSurfaceVariant,
        )
    }
}
