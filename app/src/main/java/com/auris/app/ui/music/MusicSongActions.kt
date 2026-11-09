@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.auris.app.ui.music

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auris.app.music.MusicLocalCollections
import com.auris.app.data.remote.music.MusicArtistDetails
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.player.CustomSleepTimerDialog
import com.auris.app.ui.player.PlayerViewModel
import com.auris.app.ui.player.QueueSheet
import com.auris.app.ui.player.toTimestamp
import com.auris.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Song actions and their subpages share a sheet so Back always returns to the selected song. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicSongActions(row: MusicRow, music: MusicBrowseViewModel, onExit: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val closePages = onExit ?: onDismiss
    val context = LocalContext.current
    val player: PlayerViewModel = viewModel(key = "search-song-player", factory = PlayerViewModel.factory(context))
    val playback by player.state.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val browse by music.state.collectAsStateWithLifecycle()
    val local = remember { MusicLocalCollections(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var pages by remember(row.id) { mutableStateOf(listOf("Song options")) }
    val page = pages.last()
    var favorite by remember(row.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var actionJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(emptyList<MusicRow>()) }
    var artistOptions by remember { mutableStateOf<MusicRow?>(null) }
    var currentArtistDetails by remember { mutableStateOf<MusicArtistDetails?>(null) }
    var rowHistory by remember { mutableStateOf(emptyList<List<MusicRow>>()) }
    fun open(title: String) { rowHistory = rowHistory + listOf(rows); pages = pages + title }
    fun back() {
        actionJob?.cancel()
        rows = rowHistory.lastOrNull().orEmpty()
        rowHistory = rowHistory.dropLast(1)
        pages = pages.dropLast(1)
    }
    var playlists by remember { mutableStateOf(local.playlists()) }
    var playlistName by remember { mutableStateOf("") }
    var customTimer by remember { mutableStateOf(false) }
    fun runAction(success: String? = null, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        actionJob = scope.launch {
            try { action(); message = success }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Could not complete this action. Try again." }
            finally { busy = false }
        }
    }
    LaunchedEffect(row.id) { favorite = music.isFavorite(row) }
    LaunchedEffect(browse.message) {
        browse.message?.let { message = it; music.consumeMessage() }
    }
    if (page != "Artist" && !page.startsWith("album:")) ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AurisBackground) {
        BackHandler(pages.size > 1) { back() }
        Column(Modifier.fillMaxWidth()) {
            if (pages.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(page.substringAfter(":"), style = MaterialTheme.typography.titleLarge)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, Modifier.padding(20.dp), color = AurisAccent) }
            if (page == "Queue") {
                QueueSheet(queue, playback, { player.playQueueItem(it.index) }, player::moveQueueItemBy, player::clearUpcoming)
            } else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                when (page) {
                    "Song options" -> {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            MediaArtwork(row.artworkUrl, null, Modifier.size(56.dp), shape = ArtworkShapeSmall)
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AurisOnSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(Triple("Share", Icons.Filled.Share, {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${row.title}\nhttps://music.youtube.com/watch?v=${row.id}")
                                }, "Share song"))
                            }), Triple("Queue", Icons.AutoMirrored.Filled.QueueMusic, { open("Queue") }),
                                Triple("Playlist", Icons.Filled.PlaylistAdd, { open("Playlists") })).forEach { (label, icon, action) ->
                                Surface(onClick = action, shape = MaterialTheme.shapes.medium, color = AurisSurfaceOpaque, modifier = Modifier.weight(1f)) {
                                    Column(Modifier.heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.height(4.dp)); Text(label, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                        SongAction("Play", Icons.Filled.PlayArrow, !busy) { music.play(row); onDismiss() }
                        SongAction("Play Next", Icons.Filled.PlaylistPlay, !busy) { runAction("Added to play next") { music.addToQueue(row, true) } }
                        SongAction("Add to queue", Icons.AutoMirrored.Filled.QueueMusic, !busy) { runAction("Added to queue") { music.addToQueue(row, false) } }
                        SongAction(if (favorite) "Remove from Favorites" else "Add to Favorites", Icons.Filled.FavoriteBorder, !busy) {
                            runAction { music.favorite(row, !favorite); favorite = !favorite }
                        }
                        SongSection("Playback")
                        SongAction("Equalizer", Icons.Filled.GraphicEq) { open("Equalizer") }
                        SongAction("Sleep Timer", Icons.Filled.Bedtime) { open("Sleep Timer") }
                        SongSection("Explore")
                        val hasArtist = !row.artistHint().isNullOrBlank()
                        SongAction("Go to Artist", Icons.Filled.Person, !busy && hasArtist) {
                            open("Artist")
                            rows = emptyList()
                            currentArtistDetails = null
                            runAction {
                                val (details, artistRows) = music.loadArtist(row)
                                currentArtistDetails = details
                                rows = artistRows
                            }
                        }
                        SongAction("Go to Album", Icons.Filled.Album, !busy) {
                            runAction {
                                val (albumTitle, albumTracks) = music.goToAlbum(row)
                                if (albumTitle != null && albumTracks.isNotEmpty()) {
                                    open("album:$albumTitle")
                                    rows = albumTracks
                                } else if (albumTracks.isNotEmpty()) {
                                    open("Albums")
                                    rows = albumTracks
                                } else {
                                    error("No album found for this song.")
                                }
                            }
                        }
                        SongAction("Start Radio", Icons.Filled.Radio, !busy) { runAction("Radio started") { music.radio(row) } }
                        SongSection("Manage song")
                        SongAction("Add to Library", Icons.Filled.LibraryAdd, !busy) { music.saveToLibrary(row) }
                    }
                    "Sleep Timer" -> {
                        Text("Pausing in ${playback.sleepRemainingMs.toTimestamp()}")
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            SongAction("$minutes minutes", Icons.Filled.Timer) { player.setSleepTimer(minutes) }
                        }
                        SongAction("Custom timer", Icons.Filled.Timer) { customTimer = true }
                        SongAction("Turn off timer", Icons.Filled.Close) { player.setSleepTimer(0) }
                    }
                    "Equalizer" -> {
                        if (playback.eqFrequencies.isEmpty()) Text("Play a song to activate audio effects.")
                        playback.eqFrequencies.forEachIndexed { index, frequency ->
                            var level by remember(index, playback.eqLevels) { mutableFloatStateOf(playback.eqLevels.getOrElse(index) { 0 }.toFloat()) }
                            Text("$frequency Hz")
                            Slider(level, { level = it }, onValueChangeFinished = { player.setEqualizerBand(index, level.toInt()) }, valueRange = playback.eqMin.toFloat()..playback.eqMax.toFloat())
                        }
                        TextButton(onClick = { playback.eqFrequencies.indices.forEach { player.setEqualizerBand(it, 0) } }) { Text("Reset") }
                    }
                    "Playlists" -> {
                        OutlinedTextField(playlistName, { playlistName = it }, label = { Text("New playlist name") })
                        TextButton(enabled = playlistName.isNotBlank(), onClick = {
                            local.add(playlistName.trim(), row); playlists = local.playlists(); playlistName = ""
                        }) { Text("Create playlist and add song") }
                        playlists.forEach { list ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { open("playlist:${list.name}"); rows = list.tracks }, modifier = Modifier.weight(1f)) { Text("${list.name} · ${list.tracks.size} songs") }
                                TextButton(onClick = { local.add(list.name, row, list.id); playlists = local.playlists(); message = "Song added" }) { Text("Add") }
                            }
                        }
                    }
                    else -> {
                        val cleanArtist = row.artistHint().orEmpty()
                        if (rows.isNotEmpty() && (page.startsWith("playlist:") || page.startsWith("collection:"))) {
                            SongAction("Play all", Icons.Filled.PlayArrow, !busy) { runAction { music.playPlaylist(rows.filter { it.kind == MusicRow.Kind.TRACK }) } }
                        }
                        if (page == "Artist") {
                            MediaArtwork(rows.firstOrNull { it.artworkUrl != null }?.artworkUrl ?: row.artworkUrl, null, Modifier.fillMaxWidth().height(220.dp))
                            Text(cleanArtist, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 12.dp))
                            Text("Artist songs and releases", color = AurisOnSurfaceVariant)
                            Button(enabled = !busy && rows.any { it.kind == MusicRow.Kind.TRACK }, onClick = {
                                runAction { music.playPlaylist(rows.filter { it.kind == MusicRow.Kind.TRACK }) }
                            }) { Text("Play songs") }
                        }
                        if (!busy && rows.isEmpty()) Text("No results found.")
                        val groups = if (page == "Artist") rows.groupBy { it.kind } else mapOf(MusicRow.Kind.TRACK to rows)
                        groups.forEach { (kind, entries) ->
                            if (page == "Artist") SongSection(when (kind) {
                                MusicRow.Kind.TRACK -> "Songs"
                                MusicRow.Kind.ALBUM -> "Albums"
                                MusicRow.Kind.PLAYLIST -> "Playlists"
                                MusicRow.Kind.ARTIST -> "Artists"
                            })
                            entries.forEach { item ->
                                Row(Modifier.fillMaxWidth().combinedClickable(enabled = !busy, onLongClick = {
                                    if (item.kind == MusicRow.Kind.TRACK) artistOptions = item
                                }, onClick = {
                                    if (item.kind != MusicRow.Kind.TRACK) {
                                        open("${if (item.kind == MusicRow.Kind.ALBUM) "album" else "collection"}:${item.title}"); rows = emptyList(); runAction { rows = music.collectionRows(item.id) }
                                    } else music.play(item)
                                }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MediaArtwork(item.artworkUrl, null, Modifier.size(56.dp), shape = ArtworkShapeSmall)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(item.title); Text(item.subtitle.orEmpty(), color = AurisOnSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (customTimer) CustomSleepTimerDialog({ customTimer = false }) { player.setSleepTimerDuration(it); customTimer = false }
    val pageMiniPlayer: @Composable () -> Unit = {
        if (browse.nowPlayingId != null) NowPlayingStrip(
            title = browse.nowPlayingTitle ?: "Now playing", artist = browse.nowPlayingArtist,
            artworkUrl = browse.nowPlayingArtworkUrl, isPlaying = browse.isPlaying,
            isLoading = browse.isResolving || browse.isBuffering,
            onClick = { if (!browse.isResolving) { closePages(); music.openPlayer() } },
            onTogglePlayPause = music::togglePlayPause,
            onPrevious = music::previous, onNext = music::next,
            canGoPrevious = !browse.isResolving && browse.canGoPrevious,
            canGoNext = !browse.isResolving && browse.canGoNext,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    val navigate = com.auris.app.ui.components.LocalTopLevelNavigate.current
    val pageBottomBar: @Composable () -> Unit = {
        Surface(color = AurisBackground) {
            Column {
                Box(Modifier.padding(horizontal = 16.dp)) { pageMiniPlayer() }
                com.auris.app.ui.components.FloatingNavigationBar(
                    destinations = com.auris.app.ui.navigation.TopLevelDestination.entries.toList(),
                    currentRoute = com.auris.app.ui.navigation.TopLevelDestination.MUSIC.route,
                    onNavigate = { destination -> closePages(); navigate(destination) },
                )
            }
        }
    }
    if (page == "Artist") MusicArtistPage(
        name = currentArtistDetails?.name ?: row.artistHint().orEmpty(),
        artwork = currentArtistDetails?.photoUrl ?: rows.firstOrNull { it.artworkUrl != null }?.artworkUrl ?: row.artworkUrl,
        bio = currentArtistDetails?.bio,
        rows = rows.distinctBy { it.kind to it.id },
        loading = busy,
        message = message,
        onBack = { back() },
        onPlay = { music.play(it) },
        bottomBar = pageBottomBar,
        onPlayAll = { runAction { music.playPlaylist(rows.filter { it.kind == MusicRow.Kind.TRACK }) } },
        onCollection = { item -> open("${if (item.kind == MusicRow.Kind.ALBUM) "album" else "collection"}:${item.title}"); rows = emptyList(); runAction { rows = music.collectionRows(item.id) } },
        onOptions = { artistOptions = it },
    )
    if (page.startsWith("album:")) MusicAlbumPage(
        title = page.substringAfter(":"), tracks = rows, loading = busy, message = message,
        bottomBar = pageBottomBar,
        onBack = { back() }, onPlayAll = { runAction { music.playPlaylist(rows) } },
        onPlay = { index -> runAction { music.playPlaylist(rows, index) } },
        onOptions = { artistOptions = it },
    )
    artistOptions?.let { song -> MusicSongActions(song, music, onExit = closePages) { artistOptions = null } }
}

@Composable
private fun SongAction(title: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    val contentColor = if (enabled) AurisOnSurface else AurisOnSurfaceVariant.copy(alpha = 0.5f)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (enabled) AurisOnSurfaceVariant else contentColor)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SongSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(title, style = MaterialTheme.typography.labelLarge, color = AurisOnSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
}
