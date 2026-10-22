package com.auris.app.ui.music

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * Music Search, laid out like a music service rather than a text box with a list under it.
 *
 * With no query the screen is a landing page: a two-column grid of genre tiles, each of which
 * runs the search it names. Typing (or tapping a tile) swaps the grid for results. That split
 * matters — a blank query used to fire three discovery requests and drop a flat list on screen,
 * which looked like a search result the user had not asked for.
 *
 * ### The parse counters are shown only on an empty result
 * [MusicBrowseUiState.parsedTrackCount] and friends exist to tell "the parser matched nothing"
 * apart from "the request failed". Both look identical on an empty list, so the counts belong in
 * the empty state where that distinction is the only useful thing on screen — not as a
 * permanent diagnostic strip under the header.
 */
@Composable
fun MusicBrowseScreen(
    state: MusicBrowseUiState,
    onQueryChange: (String) -> Unit,
    onPlay: (MusicRow) -> Unit,
    onSave: (MusicRow) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlayer: () -> Unit = {},
) {
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
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 56.dp, bottom = 10.dp),
            ) {
                Text(
                    text = "Search",
                    style = MaterialTheme.typography.displayLarge,
                    color = AurisOnSurface,
                )
                Spacer(Modifier.height(14.dp))
                SearchField(
                    query = state.query,
                    isLoading = state.isLoading,
                    onQueryChange = onQueryChange,
                )
            }

            state.error?.let { error ->
                MessageBanner(error, isError = true)
            }

            state.message?.let { message ->
                MessageBanner(message, isError = false)
            }

            when {
                // Blank query: the category landing. Checked before `isEmpty`, because an empty
                // result set is exactly what a blank query produces and it is not an error.
                state.isBrowsingCategories -> CategoryGrid(onCategory = { onQueryChange(it.query) })
                state.isResolving -> ResolvingRow()
                state.isEmpty -> EmptyMusic(query = state.query, state = state)
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.results, key = { "${it.kind}-${it.id}" }) { row ->
                        MusicRowItem(
                            row = row,
                            isNowPlaying = row.id == state.nowPlayingId,
                            onPlay = { onPlay(row) },
                            onSave = { onSave(row) },
                            onOpenPlayer = onOpenPlayer,
                        )
                    }
                }
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
            .clip(RoundedCornerShape(14.dp))
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

// ---- Rows --------------------------------------------------------------------

@Composable
private fun MusicRowItem(
    row: MusicRow,
    isNowPlaying: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onOpenPlayer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AurisRadii.artworkSmall))
            .background(if (isNowPlaying) AurisAccent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlay,
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art uses the same 1:1 square and corner radius as every other artwork surface.
        AsyncImage(
            model = row.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(AurisRadii.artworkSmall))
                .background(AurisTrack),
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = AurisOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.subtitle != null) {
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurisOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Non-track rows are not directly playable, so the kind is stated rather than left
            // to be inferred from an icon.
            if (row.kind != MusicRow.Kind.TRACK) {
                Text(
                    text = row.kind.name.lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AurisAccent,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (isNowPlaying) AurisAccent else AurisSurface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = if (isNowPlaying) "Open player" else "Play",
                tint = if (isNowPlaying) AurisBackground else AurisOnSurface,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (isNowPlaying) onOpenPlayer() else onPlay() },
                    ),
            )
        }
    }
}

// ---- States ------------------------------------------------------------------

@Composable
private fun ResolvingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = AurisAccent,
        )
    }
}

@Composable
private fun EmptyMusic(query: String, state: MusicBrowseUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No results for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "YouTube Music returned nothing the parser recognised.",
            style = MaterialTheme.typography.bodySmall,
            color = AurisOnSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        // Diagnostic only where it is actionable: an empty list cannot otherwise be told apart
        // from a parser that silently matched nothing.
        Text(
            text = "Parsed ${state.parsedTrackCount} tracks, " +
                "${state.parsedAlbumCount} albums, ${state.parsedPlaylistCount} playlists",
            style = MaterialTheme.typography.labelSmall,
            color = AurisOnSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun MessageBanner(message: String, isError: Boolean) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
        else AurisSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
