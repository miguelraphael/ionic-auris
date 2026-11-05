@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.auris.app.ui.music

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.auris.app.data.remote.music.nowPlayingArtworkUrl
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.theme.*

@Composable
internal fun MusicAlbumPage(title: String, tracks: List<MusicRow>, loading: Boolean,
    message: String?, onBack: () -> Unit, onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit, onOptions: (MusicRow) -> Unit, miniPlayer: @Composable () -> Unit = {}) {
    val albumArt = tracks.firstNotNullOfOrNull { it.albumArtworkUrl }
        ?: tracks.firstOrNull()?.artworkUrl
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = AurisBackground) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 40.dp)) {
                    item {
                        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(12.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MediaArtwork(nowPlayingArtworkUrl(albumArt), "Cover for $title",
                                Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f),
                                fallbackImageUrl = albumArt)
                            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 20.dp))
                            Text("Album · ${tracks.size} songs", color = AurisOnSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                            Button(onClick = onPlayAll, enabled = !loading && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Play album") }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                        message?.let { Text(it, Modifier.padding(20.dp)) }
                        if (!loading && tracks.isEmpty()) Text("No tracks available for this album.", Modifier.padding(24.dp))
                    }
                    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(enabled = !loading,
                                onClick = { onPlay(index) }, onLongClick = { onOptions(track) })
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                                .heightIn(min = 52.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("${index + 1}", Modifier.width(24.dp),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                textAlign = TextAlign.End, color = AurisOnSurfaceVariant)
                            MediaArtwork(albumArt, null, Modifier.size(48.dp), shape = ArtworkShapeSmall)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                track.artistHint()?.takeIf { it.isNotBlank() }?.let { artist ->
                                    Text(artist, style = MaterialTheme.typography.bodySmall,
                                        color = AurisOnSurfaceVariant, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(onClick = { onOptions(track) }) {
                                Icon(Icons.Filled.MoreVert, "Options for ${track.title}")
                            }
                        }
                    }
                }
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) { miniPlayer() }
            }
        }
    }
}
