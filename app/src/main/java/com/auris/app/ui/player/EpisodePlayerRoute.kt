package com.auris.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisSurface
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
    previewUnits: List<SourceUnit>? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity()
    val view = LocalView.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var landscapeRequested by rememberSaveable(entry.id) { mutableStateOf(true) }

    // Own the window for the whole route, including loading and retry screens.
    DisposableEffect(activity, view) {
        val originalOrientation = activity?.requestedOrientation
        val wasKeepingAwake = view.keepScreenOn
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val originalBehavior = controller?.systemBarsBehavior
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)
        view.keepScreenOn = true
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            view.keepScreenOn = wasKeepingAwake
            if (originalOrientation != null) activity.requestedOrientation = originalOrientation
            if (originalBehavior != null) controller.systemBarsBehavior = originalBehavior
            if (insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false)
                controller?.show(WindowInsetsCompat.Type.statusBars())
            if (insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false)
                controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }
    val requestedOrientation = if (landscapeRequested)
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    SideEffect { activity?.requestedOrientation = requestedOrientation }
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
            val chapter: LibraryUnitEntity
            if (previewUnits != null) {
                units = previewUnits.map { it.toPreviewEntity(entry.id) }
                chapter = units.firstOrNull { it.unitIndex == unitIndex }
                    ?: throw java.io.IOException("This episode is not available. Go back and refresh the episode list.")
                savedUnit = chapter
            } else {
                units = libraryRepository.observeUnits(entry.id).first()
                chapter = units.firstOrNull { it.unitIndex == unitIndex }
                    ?: throw java.io.IOException("This episode is not saved. Go back and refresh the episode list.")
                savedUnit = chapter
            }
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
        if (previewUnits != null) return@LaunchedEffect
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
                    com.auris.app.ui.components.StateActionButton("Retry with fresh links", { retry++ })
                }
                com.auris.app.ui.components.StateActionButton("Back", onBack, filled = false)
            }
        } else {
            VideoPlayerScreen(episodePlayer, entry.title, savedUnit?.title.orEmpty(),
                onBack = onBack, onError = { message = it },
                isFullscreen = isLandscape,
                onToggleFullscreen = { landscapeRequested = !isLandscape },
                headerActions = {
                    Box {
                        FilledTonalButton(onClick = { menuOpen = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AurisSurface, contentColor = AurisOnSurface),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(36.dp).widthIn(max = 140.dp)) {
                            Text(targets.getOrNull(selected)?.label ?: "Source", maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
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
                        FilledTonalButton(colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AurisSurface, contentColor = AurisOnSurface),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.height(36.dp), onClick = {
                            subtitlesEnabled = !subtitlesEnabled
                            player?.let { exo -> exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled).build() }
                        }) { Text(if (subtitlesEnabled) "CC on" else "CC off", maxLines = 1) }
                    }
            })
        }
    }
}

private fun SourceUnit.toPreviewEntity(entryId: String) = LibraryUnitEntity(
    entryId = entryId,
    unitIndex = index,
    sourceUnitId = url,
    title = title,
    thumbnailUrl = thumbnailUrl,
    durationMs = watchDurationMs,
    isWatched = isWatched,
    lastPositionMs = watchPositionMs,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
