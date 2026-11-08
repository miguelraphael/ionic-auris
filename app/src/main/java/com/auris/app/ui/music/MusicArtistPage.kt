@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.auris.app.ui.music

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.theme.*

@Composable
internal fun MusicArtistPage(
    name: String,
    artwork: String?,
    bio: String? = null,
    rows: List<MusicRow>,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onPlay: (MusicRow) -> Unit,
    onPlayAll: () -> Unit,
    onCollection: (MusicRow) -> Unit,
    onOptions: (MusicRow) -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    var showAllSongs by remember(name) { mutableStateOf(false) }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = AurisBackground) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
                    item {
                        Box {
                            MediaArtwork(artwork, null, Modifier.fillMaxWidth().height(220.dp))
                            FilledTonalIconButton(onClick = onBack, modifier = Modifier.padding(16.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back")
                            }
                        }
                        Column(Modifier.padding(20.dp)) {
                            Text(name, style = MaterialTheme.typography.headlineLarge)
                            Text("Artist", color = AurisOnSurfaceVariant)
                            if (!bio.isNullOrBlank()) {
                                var expanded by remember { mutableStateOf(false) }
                                Surface(
                                    shape = ArtworkShapeSmall,
                                    color = AurisSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                        .clickable { expanded = !expanded }
                                ) {
                                    Column(Modifier.animateContentSize(tween(AurisMotion.Standard, easing = AurisMotion.Ease)).padding(14.dp)) {
                                        Text(
                                            text = "About",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = AurisOnSurface,
                                            modifier = Modifier.padding(bottom = 6.dp),
                                        )
                                        Text(
                                            text = bio,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = AurisOnSurfaceVariant,
                                            maxLines = if (expanded) Int.MAX_VALUE else 3,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = if (expanded) "Show less" else "Read more",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = AurisAccent,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                    }
                                }
                            }
                            Button(onClick = onPlayAll, enabled = !loading && rows.any { it.kind == MusicRow.Kind.TRACK }, shape = ArtworkShapeSmall, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp)) { Text("Play songs") }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        message?.let { Text(it, Modifier.padding(20.dp)) }
                    }
                    val songs = rows.filter { it.kind == MusicRow.Kind.TRACK }
                    if (songs.isNotEmpty()) {
                        item { Text("Songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp)) }
                        items(if (showAllSongs) songs else songs.take(5), key = { it.id }) { song ->
                            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onPlay(song) }, onLongClick = { onOptions(song) }).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                MediaArtwork(song.artworkUrl, null, Modifier.size(52.dp), shape = ArtworkShapeSmall)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.subtitle.orEmpty(), style = MaterialTheme.typography.bodySmall, color = AurisOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onOptions(song) }) { Icon(Icons.Filled.MoreHoriz, "Options for ${song.title}") }
                            }
                        }
                        if (songs.size > 5) item {
                            TextButton(onClick = { showAllSongs = !showAllSongs }, modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text(if (showAllSongs) "Show fewer songs" else "Show all ${songs.size} songs")
                            }
                        }
                    }
                    listOf(MusicRow.Kind.ALBUM to "Albums", MusicRow.Kind.PLAYLIST to "Playlists").forEach { (kind, title) ->
                        val collections = rows.filter { it.kind == kind }.distinctBy { it.id }
                        if (collections.isNotEmpty()) item {
                            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(collections, key = { it.id }) { collection ->
                                    Column(Modifier.width(152.dp).combinedClickable(onClick = { onCollection(collection) })) {
                                        MediaArtwork(collection.artworkUrl, null, Modifier.size(152.dp), shape = ArtworkShapeSmall)
                                        Text(collection.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    }
                                }
                            }
                        }
                    }
                    if (!loading && rows.isEmpty()) item { Text("No artist releases found. Try searching for the artist directly.", Modifier.padding(20.dp)) }
                }
                bottomBar()
            }
        }
    }
}
