@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.auris.app.ui.music

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.auris.app.data.remote.music.nowPlayingArtworkUrl
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.theme.*

@Composable
internal fun rememberDialogNavBottomPadding(): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    val activity = remember(context) {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return@remember c
            c = c.baseContext
        }
        null
    }
    val density = LocalDensity.current
    val systemNavBottom = remember(activity, density) {
        val insets = activity?.let { ViewCompat.getRootWindowInsets(it.window.decorView) }
        val bottomPx = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        with(density) { bottomPx.toDp() }
    }
    val composeNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return maxOf(systemNavBottom, composeNavBottom)
}

@Composable
internal fun rememberDialogStatusBarTopPadding(): androidx.compose.ui.unit.Dp {
    val context = LocalContext.current
    val activity = remember(context) {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return@remember c
            c = c.baseContext
        }
        null
    }
    val density = LocalDensity.current
    val systemStatusTop = remember(activity, density) {
        val insets = activity?.let { ViewCompat.getRootWindowInsets(it.window.decorView) }
        val topPx = insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        with(density) { topPx.toDp() }
    }
    val composeStatusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return maxOf(systemStatusTop, composeStatusTop)
}

@Composable
internal fun MusicAlbumPage(
    title: String,
    tracks: List<MusicRow>,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (Int) -> Unit,
    onOptions: (MusicRow) -> Unit,
    isPlaylist: Boolean = false,
    albumArtist: String? = null,
    artworkUrl: String? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val albumArt = artworkUrl ?: tracks.firstNotNullOfOrNull { it.albumArtworkUrl }
        ?: tracks.firstOrNull()?.artworkUrl
    val navBottom = rememberDialogNavBottomPadding()
    val statusTop = rememberDialogStatusBarTopPadding()

    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AurisBackground) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp + navBottom)) {
                    item {
                        IconButton(onClick = onBack, modifier = Modifier.padding(top = statusTop + 4.dp, start = 12.dp, bottom = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back")
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MediaArtwork(nowPlayingArtworkUrl(albumArt), "Cover for $title",
                                Modifier.widthIn(max = 240.dp).fillMaxWidth().aspectRatio(1f),
                                fallbackImageUrl = albumArt)
                            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
                            val typeLabel = if (isPlaylist) "Playlist" else "Album"
                            Text("$typeLabel · ${tracks.size} songs", color = AurisOnSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                            Button(onClick = onPlayAll, enabled = !loading && tracks.isNotEmpty(), shape = ArtworkShapeSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).height(48.dp)) {
                                Icon(Icons.Filled.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (isPlaylist) "Play playlist" else "Play album")
                            }
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                        message?.let { Text(it, Modifier.padding(20.dp)) }
                        if (!loading && tracks.isEmpty()) Text(if (isPlaylist) "No tracks available for this playlist." else "No tracks available for this album.", Modifier.padding(24.dp))
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
                            val trackArtwork = if (isPlaylist) (track.artworkUrl ?: track.albumArtworkUrl ?: albumArt) else (albumArt ?: track.artworkUrl)
                            MediaArtwork(trackArtwork, null, Modifier.size(48.dp), shape = ArtworkShapeSmall)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(track.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val artist = track.artistHint()?.takeIf { it.isNotBlank() } ?: albumArtist
                                artist?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = AurisOnSurfaceVariant, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                            IconButton(onClick = { onOptions(track) }) {
                                Icon(Icons.Filled.MoreHoriz, "Options for ${track.title}")
                            }
                        }
                    }
                }
                Box(Modifier.padding(bottom = navBottom)) {
                    bottomBar()
                }
            }
        }
    }
}
