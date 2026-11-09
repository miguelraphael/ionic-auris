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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auris.app.AurisApplication
import com.auris.app.data.local.entity.PlaylistEntity
import com.auris.app.data.local.entity.PlaylistTrackEntity
import com.auris.app.data.repository.PlaylistRepository
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
    val playlistRepository = remember { (context.applicationContext as AurisApplication).container.playlistRepository }
    val playlists by playlistRepository.observePlaylists().collectAsStateWithLifecycle(initialValue = emptyList())
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
    var renameTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistEntity?>(null) }
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
            val playlistHeader = page.takeIf { it.startsWith("playlist:") }?.substringAfter("playlist:")
                ?.let { id -> playlists.firstOrNull { it.id == id } }
            val headerTitle = playlistHeader?.name ?: if (page.startsWith("playlist:")) "Playlist" else page.substringAfter(":")
            if (pages.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text(headerTitle, style = MaterialTheme.typography.titleLarge)
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
                                Triple("Add to playlist", Icons.Filled.PlaylistAdd, { open("Playlists") })).forEach { (label, icon, action) ->
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
                            val name = playlistName.trim()
                            playlistName = ""
                            runAction("Playlist created and song added") {
                                val created = playlistRepository.createPlaylist(name)
                                playlistRepository.addTrack(created.id, row.id, row.title, row.subtitle, row.artworkUrl)
                            }
                        }) { Text("Create playlist") }
                        playlists.forEach { playlist ->
                            SongPlaylistRow(
                                playlist = playlist,
                                repository = playlistRepository,
                                songId = row.id,
                                onOpen = { open("playlist:${playlist.id}") },
                                onAdd = { runAction("Song added") { playlistRepository.addTrack(playlist.id, row.id, row.title, row.subtitle, row.artworkUrl) } },
                                onAlreadyAdded = { message = "Already in playlist" },
                                onRename = { renameTarget = playlist },
                                onDelete = { deleteTarget = playlist },
                            )
                        }
                    }
                    else -> {
                        val playlistId = page.takeIf { it.startsWith("playlist:") }?.substringAfter("playlist:")
                        if (playlistId != null) {
                            PlaylistDetailSection(
                                playlistId = playlistId,
                                repository = playlistRepository,
                                busy = busy,
                                onPlayAll = { items -> runAction { music.playPlaylist(items) } },
                                onPlay = { music.play(it) },
                                onOptions = { artistOptions = it },
                                onRemove = { trackId -> runAction("Removed from playlist") { playlistRepository.removeTrack(playlistId, trackId) } },
                            )
                        }
                        val cleanArtist = row.artistHint().orEmpty()
                        if (rows.isNotEmpty() && page.startsWith("collection:")) {
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
                        if (playlistId == null && !busy && rows.isEmpty()) Text("No results found.")
                        val groups = if (playlistId != null) emptyMap() else if (page == "Artist") rows.groupBy { it.kind } else mapOf(MusicRow.Kind.TRACK to rows)
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
    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("Playlist name") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank() && newName.trim() != target.name,
                    onClick = {
                        val name = newName.trim()
                        renameTarget = null
                        runAction("Playlist renamed") { playlistRepository.renamePlaylist(target.id, name) }
                    },
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${target.name}\" and all of its songs will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        runAction("Playlist deleted") { playlistRepository.deletePlaylist(target.id) }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
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
            modifier = Modifier.padding(bottom = com.auris.app.ui.components.FloatingNavigationLayout.playerGap),
        )
    }
    val navigate = com.auris.app.ui.components.LocalTopLevelNavigate.current
    val pageBottomBar: @Composable () -> Unit = {
        Surface(color = Color.Transparent) {
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

@Composable
private fun SongPlaylistRow(
    playlist: PlaylistEntity,
    repository: PlaylistRepository,
    songId: String,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onAlreadyAdded: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val tracks by repository.observeTracks(playlist.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val alreadyAdded = tracks.any { it.trackId == songId }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { if (alreadyAdded) onAlreadyAdded() else onAdd() }).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(tracks.firstOrNull()?.artworkUrl, null, Modifier.size(56.dp), shape = ArtworkShapeSmall)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tracks.size} songs", color = AurisOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (alreadyAdded) "Already in playlist" else "Tap to add", color = AurisOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.KeyboardArrowRight, "Open ${playlist.name}") }
        IconButton(onClick = onRename, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Edit, "Rename ${playlist.name}") }
        IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, "Delete ${playlist.name}") }
    }
}

@Composable
private fun PlaylistDetailSection(
    playlistId: String,
    repository: PlaylistRepository,
    busy: Boolean,
    onPlayAll: (List<MusicRow>) -> Unit,
    onPlay: (MusicRow) -> Unit,
    onOptions: (MusicRow) -> Unit,
    onRemove: (String) -> Unit,
) {
    val tracks by repository.observeTracks(playlistId).collectAsStateWithLifecycle(initialValue = emptyList())
    if (tracks.isNotEmpty()) SongAction("Play all", Icons.Filled.PlayArrow, !busy) { onPlayAll(tracks.map { it.toMusicRow() }) }
    if (tracks.isEmpty() && !busy) Text("This playlist is empty.")
    tracks.forEach { track ->
        val item = track.toMusicRow()
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    enabled = !busy,
                    onLongClick = { onOptions(item) },
                    onClick = { onPlay(item) },
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtwork(track.artworkUrl, null, Modifier.size(56.dp), shape = ArtworkShapeSmall)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                track.artist?.let { Text(it, color = AurisOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            TextButton(onClick = { onRemove(track.trackId) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
        }
    }
}

private fun PlaylistTrackEntity.toMusicRow(): MusicRow = MusicRow(
    kind = MusicRow.Kind.TRACK,
    title = title,
    subtitle = artist,
    artworkUrl = artworkUrl,
    id = trackId,
)
