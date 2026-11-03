package com.auris.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import com.auris.app.core.model.EntryStatus
import com.auris.app.core.model.MediaType
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.LibraryUnitEntity
import com.auris.app.data.repository.ExtensionContentRepository
import com.auris.app.data.repository.LibraryRepository
import com.auris.app.source.SourceUnit
import com.auris.app.source.StreamTarget
import com.auris.app.ui.theme.AurisAccent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Resolve the actual stored episode once per open/retry; stream URLs never enter the database. */
@Composable
fun EpisodePlayerRoute(
    entry: LibraryEntryEntity,
    unitIndex: Int,
    libraryRepository: LibraryRepository,
    contentRepository: ExtensionContentRepository,
    episodePlayer: EpisodePlayer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var message by remember(entry.id, unitIndex) { mutableStateOf<String?>(null) }
    var savedUnit by remember(entry.id, unitIndex) { mutableStateOf<LibraryUnitEntity?>(null) }
    var units by remember(entry.id, unitIndex) { mutableStateOf<List<LibraryUnitEntity>>(emptyList()) }
    var targets by remember(entry.id, unitIndex) { mutableStateOf<List<StreamTarget>>(emptyList()) }
    var retry by remember(entry.id, unitIndex) { mutableIntStateOf(0) }
    var selected by remember(entry.id, unitIndex) { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var subtitlesEnabled by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner, episodePlayer) {
        lifecycleOwner.lifecycle.addObserver(episodePlayer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(episodePlayer)
            episodePlayer.release()
        }
    }

    LaunchedEffect(entry.id, unitIndex, retry) {
        episodePlayer.release()
        message = null
        targets = emptyList()
        try {
            units = libraryRepository.observeUnits(entry.id).first()
            val chapter = units.firstOrNull { it.unitIndex == unitIndex }
                ?: throw java.io.IOException("This episode is not saved. Go back and refresh the episode list.")
            savedUnit = chapter
            val url = chapter.sourceUnitId?.takeIf { it.isNotBlank() }
                ?: throw java.io.IOException("The source did not provide an episode link. Refresh the episode list.")
            val resolved = contentRepository.fetchStreams(entry, SourceUnit(url, chapter.unitIndex, chapter.title)).getOrThrow()
            ensureActive()
            if (resolved.isEmpty()) throw java.io.IOException("No playable stream is available for this episode.")
            targets = resolved
            selected = 0
            subtitlesEnabled = true
            episodePlayer.play(resolved, chapter.lastPositionMs)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            message = error.message ?: "Could not load this episode. Please retry."
        }
    }

    val player by episodePlayer.player.collectAsStateWithLifecycle()
    LaunchedEffect(player, savedUnit) {
        val exo = player ?: return@LaunchedEffect
        val chapter = savedUnit ?: return@LaunchedEffect
        var previousPosition = -1L
        while (isActive) {
            val position = exo.currentPosition.coerceAtLeast(0L)
            val duration = exo.duration
            if (duration > 0L && position != previousPosition) {
                val complete = exo.playbackState == Player.STATE_ENDED || position >= duration * 0.95
                val ordinal = units.indexOfFirst { it.unitIndex == unitIndex }.coerceAtLeast(0)
                libraryRepository.updateUnitProgress(entry.id, unitIndex, chapter.isWatched || complete, position)
                libraryRepository.updateProgress(entry.id,
                    (ordinal + (position.toFloat() / duration).coerceIn(0f, 1f)) / units.size.coerceAtLeast(1),
                    unitIndex, chapter.title, position,
                    if (complete && ordinal == units.lastIndex) EntryStatus.COMPLETED else EntryStatus.IN_PROGRESS,
                    mediaType = MediaType.ANIME)
                previousPosition = position
            }
            delay(3_000)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (player == null || message != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (message == null) {
                    CircularProgressIndicator(color = AurisAccent)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading episode…", color = Color.White)
                } else {
                    Text(message.orEmpty(), color = Color.White)
                    Button(onClick = { retry++ }) { Text("Retry with fresh links") }
                }
                TextButton(onClick = onBack) { Text("Back") }
            }
        } else {
            VideoPlayerScreen(episodePlayer, entry.title, savedUnit?.title.orEmpty(),
                onBack = onBack, onError = { message = it })
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
                Box {
                    TextButton(onClick = { menuOpen = true }) {
                        Text(targets.getOrNull(selected)?.label ?: "Source", color = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        targets.forEachIndexed { index, target ->
                            DropdownMenuItem(text = { Text(target.label) }, onClick = {
                                val position = player?.currentPosition ?: 0L
                                selected = index
                                subtitlesEnabled = true
                                menuOpen = false
                                episodePlayer.play(listOf(target), position)
                            })
                        }
                    }
                }
                if (targets.getOrNull(selected)?.subtitles?.isNotEmpty() == true) {
                    TextButton(onClick = {
                        subtitlesEnabled = !subtitlesEnabled
                        player?.let { exo -> exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled).build() }
                    }) { Text(if (subtitlesEnabled) "CC on" else "CC off", color = Color.White) }
                }
            }
        }
    }
}
