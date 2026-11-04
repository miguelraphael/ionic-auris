 package com.auris.app.ui.source

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import com.auris.app.ui.theme.AurisMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import com.auris.app.ui.components.SkeletonPosterGrid
import com.auris.app.ui.components.SlowNetworkHint
import com.auris.app.ui.components.StateEmpty
import com.auris.app.ui.components.StateError
import com.auris.app.ui.components.StateNoResults
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.AurisAccent
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
    onItemClick: (SourceItem) -> Unit,
    onModeChange: (CatalogueUiState.Mode) -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Returns to the extensions list; this screen draws no nav pill. */
    onBack: () -> Unit = {},
    onLoadMore: () -> Unit = {},
) {
    val palette = rememberArtworkPaletteValue(artworkKey = state.items.firstOrNull()?.thumbnailUrl)

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.8f,
        fadeEndFraction = 0.4f,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize()) {
            CatalogueHeader(state, onBack)

            ModeTabs(selected = state.mode, onSelect = onModeChange)

            CatalogueSearchField(
                query = state.query,
                onQueryChange = onQueryChange,
            )

            SlowNetworkHint(isLoading = state.isLoading)

            when {
                state.error != null -> CatalogueError(state.error, onRetry)
                state.isLoading && state.items.isEmpty() -> LoadingGrid()
                state.isEmpty -> EmptyCatalogue(
                    state = state,
                    onClear = { onQueryChange("") },
                    onRetry = onRetry,
                )
                else -> CatalogueGrid(state, onItemClick = onItemClick, onLoadMore = onLoadMore)
            }
        }
    }


}

// ---- Header ------------------------------------------------------------------

@Composable
private fun CatalogueHeader(state: CatalogueUiState, onBack: () -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 56.dp)) {
        androidx.compose.material3.FilledTonalIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AurisOnSurface)
        }
        Spacer(Modifier.height(12.dp))
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

    val selection = animateFloatAsState(
        targetValue = if (selected == CatalogueUiState.Mode.LATEST) 1f else 0f,
        animationSpec = tween(AurisMotion.Standard, easing = AurisMotion.Ease),
        label = "catalogueSelection",
    )
    BoxWithConstraints(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(AurisRadii.chip))
            .background(AurisSurface)
            .padding(4.dp),
    ) {
        val segmentWidth = maxWidth / 2
        Box(Modifier
            .offset { IntOffset((segmentWidth.roundToPx() * selection.value).toInt(), 0) }
            .width(segmentWidth)
            .fillMaxHeight()
            .clip(RoundedCornerShape(AurisRadii.chip - 4.dp))
            .background(AurisOnSurface.copy(alpha = 0.16f)))
        Row(Modifier.fillMaxSize().selectableGroup()) {
            modes.forEach { (mode, label) ->
                val isSelected = selected == mode ||
                    (mode == CatalogueUiState.Mode.POPULAR && selected == CatalogueUiState.Mode.SEARCH)
                val tint by animateColorAsState(
                    if (isSelected) AurisOnSurface else AurisOnSurfaceVariant,
                    tween(AurisMotion.Quick), label = "catalogueLabel",
                )
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(AurisRadii.chip - 4.dp))
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(mode) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CatalogueSearchField(query: String, onQueryChange: (String) -> Unit) {
    // Cursor pinned to the end; see ExtensionSearchField for why the default
    // start selection reverses typed input on every query recomposition.
    var field by remember(query) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                query,
                androidx.compose.ui.text.TextRange(query.length),
            )
        )
    }

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
private fun CatalogueGrid(state: CatalogueUiState, onItemClick: (SourceItem) -> Unit, onLoadMore: () -> Unit) {
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
                    text = if (state.isLoading) "Loading…" else "Load more",
                    style = MaterialTheme.typography.labelSmall,
                    color = AurisOnSurfaceVariant,
                    modifier = Modifier.clickable(enabled = !state.isLoading, onClick = onLoadMore)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LoadingGrid() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SkeletonPosterGrid(rows = 2, columns = 2)
    }
}

@Composable
private fun CatalogueError(message: String, onRetry: () -> Unit) {
    StateError(
        title = "Could not load this catalogue",
        description = message,
        onRetry = onRetry,
    )
}

@Composable
private fun EmptyCatalogue(state: CatalogueUiState, onClear: () -> Unit, onRetry: () -> Unit) {
    if (state.isSearching) {
        StateNoResults(
            query = state.query,
            description = "This source returned no items. Try a shorter query or a different " +
                "spelling.",
            onClear = onClear,
        )
    } else {
        StateEmpty(
            title = "Nothing here",
            description = "This source returned no items. Try another tab or refresh in a " +
                "moment.",
            actionLabel = "Retry",
            onAction = onRetry,
        )
    }
}
