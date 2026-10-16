package com.auris.app.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisOutline
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * Music browse and search.
 *
 * ### The parse counters are shown on purpose
 * [MusicBrowseUiState.parsedTrackCount] and friends appear under the header while debugging the
 * InnerTube parser. An empty result list looks identical whether the parser is working or
 * silently matching nothing, and those are exactly the two cases that need telling apart. It
 * costs one line and it is the difference between "the screen is broken" and "YouTube returned
 * a shape the parser does not recognise".
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
            SearchField(
                query = state.query,
                isLoading = state.isLoading,
                onQueryChange = onQueryChange,
            )

            state.error?.let { error ->
                MessageBanner(error, isError = true)
            }

            state.message?.let { message ->
                MessageBanner(message, isError = false)
            }

            // Parse diagnostics, visible only while something is actually happening.
            if (state.parsedTrackCount + state.parsedAlbumCount + state.parsedPlaylistCount > 0) {
                ParseSummary(
                    tracks = state.parsedTrackCount,
                    albums = state.parsedAlbumCount,
                    playlists = state.parsedPlaylistCount,
                )
            }

            when {
                state.isResolving -> ResolvingRow()
                state.isEmpty -> EmptyMusic(state.query)
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 140.dp,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 52.dp),
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
            .background(if (isNowPlaying) AurisAccent.copy(alpha = 0.10f) else Color_Transparent)
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
private fun ParseSummary(tracks: Int, albums: Int, playlists: Int) {
    Text(
        // Diagnostic line: an empty list looks the same whether the parser works or matched
        // nothing, and those are exactly the cases that need distinguishing.
        text = "Parsed $tracks track${if (tracks == 1) "" else "s"}, " +
            "$albums album${if (albums == 1) "" else "s"}, " +
            "$playlists playlist${if (playlists == 1) "" else "s"}",
        style = MaterialTheme.typography.labelSmall,
        color = AurisOnSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

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
private fun EmptyMusic(query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (query.isBlank()) "Nothing to show" else "No results for \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "YouTube Music returned nothing the parser recognised.",
            style = MaterialTheme.typography.bodySmall,
            color = AurisOnSurfaceVariant,
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

/** Transparent placeholder so the now-playing tint can be applied conditionally. */
private val Color_Transparent = androidx.compose.ui.graphics.Color.Transparent