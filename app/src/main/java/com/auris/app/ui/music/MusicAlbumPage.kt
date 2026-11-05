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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.theme.*

@Composable
internal fun MusicAlbumPage(title: String, tracks: List<MusicRow>, loading: Boolean,
    message: String?, onBack: () -> Unit, onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit, onOptions: (MusicRow) -> Unit, miniPlayer: @Composable () -> Unit = {}) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AurisBackground) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 40.dp)) {
                    item {
                        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(12.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MediaArtwork(tracks.firstOrNull()?.artworkUrl, null, Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
                            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 20.dp))
                            Text("Album · ${tracks.size} songs", color = AurisOnSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                            Button(onClick = onPlayAll, enabled = !loading && tracks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Play album") }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                        message?.let { Text(it, Modifier.padding(20.dp)) }
                        if (!loading && tracks.isEmpty()) Text("No tracks available for this album.", Modifier.padding(24.dp))
                    }
                    itemsIndexed(tracks, key = { index, track -> "$index-${track.id}" }) { index, track ->
                        Row(Modifier.fillMaxWidth().combinedClickable(enabled = !loading,
                            onClick = { onPlay(index) }, onLongClick = { onOptions(track) }).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.width(32.dp), color = AurisOnSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium)
                                Text(track.subtitle.orEmpty(), color = AurisOnSurfaceVariant)
                            }
                            IconButton(onClick = { onOptions(track) }) { Icon(Icons.Filled.MoreVert, "Options for ${track.title}") }
                        }
                    }
                }
                Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) { miniPlayer() }
            }
        }
    }
}
