package com.auris.app.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auris.app.ui.components.ErrorBanner
import com.auris.app.ui.components.StateNoResults
import com.auris.app.ui.components.SuccessBanner
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/** Music search with a featured match, song rows, and album/playlist shelves. */
@Composable
fun MusicBrowseScreen(
    state: MusicBrowseUiState,
    onQueryChange: (String) -> Unit,
    onPlay: (MusicRow) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlayer: () -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onOptions: (MusicRow) -> Unit = {},
) {
    var filterHistory by rememberSaveable(state.query) { mutableStateOf(listOf(MusicSearchFilter.TOP.name)) }
    val filter = MusicSearchFilter.valueOf(filterHistory.last())
    val selectFilter: (MusicSearchFilter) -> Unit = { next ->
        if (next == MusicSearchFilter.TOP) filterHistory = listOf(next.name)
        else if (next != filter) filterHistory = filterHistory + next.name
    }
    BackHandler(enabled = !state.isBrowsingCategories && filterHistory.size > 1) {
        filterHistory = filterHistory.dropLast(1)
    }
    // The gradient follows the most recently tapped artwork, matching every other screen.
    val focusedArtwork = state.results.firstOrNull { it.id == state.nowPlayingId }?.artworkUrl
        ?: state.results.firstOrNull()?.artworkUrl
    val palette = rememberArtworkPaletteValue(artworkKey = focusedArtwork)

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.8f,
        fadeEndFraction = 0.4f,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 56.dp, bottom = 10.dp),
                ) {
                    if (state.isBrowsingCategories) {
                        Text("Search", style = MaterialTheme.typography.displayLarge, color = AurisOnSurface)
                        Spacer(Modifier.height(14.dp))
                    }
                    SearchField(
                        query = state.query,
                        isLoading = state.isLoading,
                        onQueryChange = onQueryChange,
                    )
                    if (!state.isBrowsingCategories) {
                        Spacer(Modifier.height(12.dp))
                        MusicSearchFilters(filter, selectFilter)
                    }
                }

                state.error?.let { error ->
                    ErrorBanner(
                        message = error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                state.message?.let { message ->
                    SuccessBanner(
                        message = message,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                when {
                    // Blank query: the category landing. Checked before `isEmpty`, because an empty
                    // result set is exactly what a blank query produces and it is not an error.
                    state.isBrowsingCategories -> CategoryGrid(onCategory = { onQueryChange(it.query) })
                    state.isEmpty -> EmptyMusic(
                        query = state.query,
                        onClear = { onQueryChange("") },
                    )
                    else -> MusicSearchResults(
                        state = state, filter = filter, onFilter = selectFilter,
                        onPlay = onPlay,
                        onOptions = onOptions,
                        onOpenPlayer = { if (!state.isResolving) onOpenPlayer() },
                    )
                }
            }
            if (state.nowPlayingId != null) {
                NowPlayingStrip(
                    title = state.nowPlayingTitle ?: "Now playing",
                    artist = state.nowPlayingArtist,
                    artworkUrl = state.nowPlayingArtworkUrl,
                    isPlaying = state.isPlaying,
                    isLoading = state.isResolving || state.isBuffering,
                    onClick = { if (!state.isResolving) onOpenPlayer() },
                    onTogglePlayPause = onTogglePlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    canGoPrevious = !state.isResolving && state.canGoPrevious,
                    canGoNext = !state.isResolving && state.canGoNext,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 104.dp),
                )
            }
        }
    }
}

// ---- Category landing --------------------------------------------------------

@Composable
private fun CategoryGrid(onCategory: (MusicCategory) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        gridItems(MUSIC_CATEGORIES, key = { it.title }) { category ->
            CategoryTile(category = category, onClick = { onCategory(category) })
        }
    }
}

/**
 * One genre tile: a colour field with the title over its dark end.
 *
 * The gradient runs bright at the top to deep at the bottom so the white title always sits on
 * the dark half, whichever palette the category uses — the Charts tile is yellow at the top and
 * still legible at the bottom.
 */
@Composable
private fun CategoryTile(category: MusicCategory, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp)
            .clip(RoundedCornerShape(AurisRadii.artwork))
            .background(Brush.verticalGradient(category.colors))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(14.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Search field ------------------------------------------------------------

@Composable
private fun SearchField(
    query: String,
    isLoading: Boolean,
    onQueryChange: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisSurface,
        modifier = Modifier.fillMaxWidth(),
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
                if (query.isEmpty()) {
                    Text(
                        text = "Search YouTube Music",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AurisOnSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = AurisOnSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    cursorBrush = SolidColor(AurisAccent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isLoading) {
                Spacer(Modifier.width(10.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = AurisAccent,
                )
            }

            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear",
                    tint = AurisOnSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onQueryChange("") },
                        ),
                )
            }
        }
    }
}

// ---- States ------------------------------------------------------------------

@Composable
private fun EmptyMusic(query: String, onClear: () -> Unit) {
    StateNoResults(
        query = query,
        description = "Try a song, artist, album, or playlist name.",
        onClear = onClear,
    )
}
