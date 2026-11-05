@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.auris.app.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auris.app.data.remote.music.MusicResultType
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.SkeletonListRows
import com.auris.app.ui.theme.*

internal enum class MusicSearchFilter(val label: String) {
    TOP("Top Results"), SONGS("Songs"), ALBUMS("Albums"), PLAYLISTS("Playlists"),
}

internal class MusicSearchSections(rows: List<MusicRow>) {
    private val unique = rows.filter { it.id.isNotBlank() }
        .sortedBy { it.resultType.searchPriority }.distinctBy { it.kind to it.id }
    val songs = unique.filter { it.kind == MusicRow.Kind.TRACK }
    val albums = unique.filter { it.kind == MusicRow.Kind.ALBUM }
    val playlists = unique.filter { it.kind == MusicRow.Kind.PLAYLIST }
    val topResult = songs.firstOrNull() ?: albums.firstOrNull() ?: playlists.firstOrNull()
}

@Composable
internal fun MusicSearchFilters(selected: MusicSearchFilter, onSelect: (MusicSearchFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(MusicSearchFilter.entries, key = { it.name }) { filter ->
            FilterChip(selected = selected == filter, onClick = { onSelect(filter) },
                label = { Text(filter.label) }, shape = CircleShape)
        }
    }
}

@Composable
internal fun MusicSearchResults(
    state: MusicBrowseUiState,
    filter: MusicSearchFilter,
    onFilter: (MusicSearchFilter) -> Unit,
    onPlay: (MusicRow) -> Unit,
    onOpenPlayer: () -> Unit,
    onOptions: (MusicRow) -> Unit,
) {
    val sections = remember(state.results) { MusicSearchSections(state.results) }
    val songs = if (filter == MusicSearchFilter.TOP) sections.songs.take(5) else sections.songs
    val empty = when (filter) {
        MusicSearchFilter.TOP -> sections.topResult == null
        MusicSearchFilter.SONGS -> sections.songs.isEmpty()
        MusicSearchFilter.ALBUMS -> sections.albums.isEmpty()
        MusicSearchFilter.PLAYLISTS -> sections.playlists.isEmpty()
    }
    if (filter == MusicSearchFilter.PLAYLISTS && !empty) {
        LazyVerticalGrid(
            modifier = Modifier.navigationBarsPadding(),
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                bottom = if (state.nowPlayingId != null) 210.dp else 140.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "playlists-header", span = { GridItemSpan(maxLineSpan) }) { SearchSectionHeader("Playlists") }
            gridItems(sections.playlists, key = { it.id }) { row ->
                SearchCollectionCard(row, { onPlay(row) }, Modifier.fillMaxWidth())
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.navigationBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = if (state.nowPlayingId != null) 210.dp else 140.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (empty) {
            item(key = "empty") {
                if (state.isLoading) SkeletonListRows() else Column(Modifier.padding(vertical = 24.dp)) {
                    Text("No ${filter.label.lowercase()} found", style = MaterialTheme.typography.titleLarge)
                    Text("Try another search or choose Top Results.", color = AurisOnSurfaceVariant)
                    if (filter != MusicSearchFilter.TOP) TextButton(onClick = { onFilter(MusicSearchFilter.TOP) }) {
                        Text("Show Top Results")
                    }
                }
            }
        }
        if (filter == MusicSearchFilter.TOP) sections.topResult?.let { top ->
            item(key = "top-header", contentType = "header") { SearchSectionHeader("Top Results") }
            item(key = "top-result", contentType = "featured") {
                Surface(shape = ArtworkShape, color = AurisSurface,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onLongClick = {
                        if (top.kind == MusicRow.Kind.TRACK) onOptions(top)
                    }, onClick = {
                        if (top.id == state.nowPlayingId) onOpenPlayer() else onPlay(top)
                    })) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        MediaArtwork(top.artworkUrl, null, Modifier.size(88.dp), shape = ArtworkShapeSmall)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(top.title, style = MaterialTheme.typography.titleLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(when (top.kind) {
                                MusicRow.Kind.TRACK -> when (top.resultType) {
                                    MusicResultType.OFFICIAL_VIDEO -> "Music video"
                                    MusicResultType.USER_VIDEO -> "Video"
                                    else -> "Song"
                                }
                                MusicRow.Kind.ALBUM -> "Album"
                                MusicRow.Kind.PLAYLIST -> "Playlist"
                            }, top.subtitle).joinToString(" · "), color = AurisOnSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { if (top.id == state.nowPlayingId) onOpenPlayer() else onPlay(top) },
                            modifier = Modifier.background(AurisAccent, CircleShape)) {
                            Icon(Icons.Filled.PlayArrow, "Play ${top.title}", tint = AurisBackground)
                        }
                    }
                }
            }
        }
        if (filter in listOf(MusicSearchFilter.TOP, MusicSearchFilter.SONGS) && songs.isNotEmpty()) {
            item(key = "songs-header", contentType = "header") {
                SearchSectionHeader("Songs", if (filter == MusicSearchFilter.TOP) ({ onFilter(MusicSearchFilter.SONGS) }) else null)
            }
            items(songs, key = { "song-${it.id}" }, contentType = { "song" }) { song ->
                SearchSongRow(song, song.id == state.nowPlayingId,
                    onPlay = { if (song.id == state.nowPlayingId) onOpenPlayer() else onPlay(song) },
                    onOptions = { onOptions(song) })
            }
        }
        listOf(Triple(MusicSearchFilter.ALBUMS, "Albums", sections.albums),
            Triple(MusicSearchFilter.PLAYLISTS, "Playlists", sections.playlists)).forEach { (kind, title, rows) ->
            if (rows.isNotEmpty() && (filter == MusicSearchFilter.TOP || filter == kind)) {
                item(key = "$title-header", contentType = "header") {
                    SearchSectionHeader(title, if (filter == MusicSearchFilter.TOP) ({ onFilter(kind) }) else null)
                }
                item(key = "$title-shelf", contentType = "shelf") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rows, key = { it.id }) { row ->
                            SearchCollectionCard(row, { onPlay(row) }, Modifier.width(152.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCollectionCard(row: MusicRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        MediaArtwork(row.artworkUrl, null, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(row.title, style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        row.subtitle?.let { Text(it, color = AurisOnSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SearchSectionHeader(title: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (onClick != null) Icon(Icons.Filled.ChevronRight, "Show all $title", Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SearchSongRow(row: MusicRow, isCurrent: Boolean, onPlay: () -> Unit, onOptions: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(ArtworkShapeSmall)
        .then(if (isCurrent) Modifier.background(AurisAccent.copy(alpha = 0.10f)) else Modifier)
        .combinedClickable(onClick = onPlay, onLongClick = onOptions).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MediaArtwork(row.artworkUrl, null, Modifier.size(52.dp), shape = ArtworkShapeSmall)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.subtitle?.let { Text(it, color = AurisOnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onOptions) { Icon(Icons.Filled.MoreVert, "Options for ${row.title}") }
    }
}
