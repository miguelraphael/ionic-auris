@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.auris.app.ui.music

import com.auris.app.ui.theme.pressFeedback
import com.auris.app.ui.theme.AurisMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.navigationBarsPadding
import com.auris.app.ui.components.FloatingNavigationLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auris.app.AurisApplication
import com.auris.app.data.local.entity.LibraryEntryEntity
import com.auris.app.data.local.entity.PlaylistEntity
import com.auris.app.data.local.entity.PlaylistTrackEntity
import com.auris.app.data.remote.music.nowPlayingArtworkUrl
import com.auris.app.data.repository.PlaylistRepository
import com.auris.app.ui.components.MediaArtwork
import com.auris.app.ui.components.SkeletonShelf
import com.auris.app.ui.components.SlowNetworkHint
import com.auris.app.ui.components.StateEmpty
import com.auris.app.ui.components.StateError
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.ArtworkShape
import com.auris.app.ui.theme.ArtworkShapeSmall
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * The Music pillar's landing page.
 *
 * A shelf layout rather than a grid of saved rows: the point of a home screen is to suggest
 * something to play, and a library listing can only offer what the user already found. Saved
 * tracks are still here as "Recently played", so nothing became less reachable — search simply
 * moved behind the search field at the top, where a music app puts it.
 *
 * ### Shelves fill in progressively
 * [MusicBrowseUiState.shelves] is written after each seed answers, and this screen renders
 * whatever is present. Waiting for all five searches before showing anything would leave the
 * user on a spinner for several seconds to avoid a little reflow.
 */
@Composable
fun MusicHomeScreen(
    state: MusicBrowseUiState,
    savedMusic: List<LibraryEntryEntity>,
    onPlay: (MusicRow) -> Unit,
    onPlayEntry: (LibraryEntryEntity) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlayer: () -> Unit = {},
    onTogglePlayPause: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    /** A failed home load is retryable; the ViewModel otherwise fetches it only once. */
    onRetryHome: () -> Unit = {},
    onOptions: (MusicRow) -> Unit = {},
    onOpenAlbum: (MusicRow) -> Unit = {},
    onOpenArtist: (MusicRow) -> Unit = {},
    /**
     * Playback for the local "Your playlists" detail's Play all / track taps. The default
     * falls back to single-track [onPlay]; the NavHost should wire full queue playback
     * (`playPlaylist`) for complete behavior.
     */
    onPlayTracks: (List<MusicRow>, Int) -> Unit = { rows, index -> rows.getOrNull(index)?.let { onPlay(it) } },
) {
    val heroArtwork = state.shelves.firstOrNull()?.rows?.firstOrNull()?.artworkUrl
    val palette = rememberArtworkPaletteValue(artworkKey = heroArtwork)
    val showNowPlaying = state.nowPlayingId != null
    val context = LocalContext.current
    val playlistRepository = remember {
        (context.applicationContext as AurisApplication).container.playlistRepository
    }
    val playlists by playlistRepository.observePlaylists().collectAsStateWithLifecycle(initialValue = emptyList())
    // Saveable so the open playlist survives rotation; cleared on Back, restoring Home.
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.75f,
        fadeEndFraction = 0.45f,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(
                    // Clear the mini player above the navigation pill when it is showing.
                    bottom = if (showNowPlaying) 196.dp else 140.dp,
                ),
            ) {
                item(key = "header") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 56.dp),
                    ) {
                        Text(
                            text = "Music",
                            style = MaterialTheme.typography.displayLarge,
                            color = AurisOnSurface,
                        )
                        Text(
                            text = "Made for you",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AurisOnSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        SearchEntry(onClick = onOpenSearch)
                    }
                }

                if (savedMusic.isNotEmpty()) {
                    item(key = "recent") {
                        ShelfHeader("Recently played")
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(savedMusic, key = { it.id }) { entry ->
                                MusicCard(
                                    artworkUrl = entry.artworkUrl ?: entry.localArtworkPath,
                                    title = entry.title,
                                    subtitle = entry.subtitle ?: entry.author,
                                    isNowPlaying = false,
                                    onClick = { onPlayEntry(entry) },
                                    onLongClick = { onOptions(MusicRow(MusicRow.Kind.TRACK, entry.title, entry.author, entry.artworkUrl, entry.sourceId)) },
                                )
                            }
                        }
                    }
                }

                if (playlists.isNotEmpty()) {
                    item(key = "your-playlists") {
                        ShelfHeader("Your playlists")
                        playlists.forEach { playlist ->
                            YourPlaylistRow(
                                playlist = playlist,
                                repository = playlistRepository,
                                onOpen = { selectedPlaylistId = playlist.id },
                            )
                        }
                    }
                }

                items(state.shelves, key = { it.title }) { shelf ->
                    ShelfHeader(shelf.title)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(shelf.rows, key = { "${it.kind}-${it.id}" }) { row ->
                            MusicCard(
                                artworkUrl = row.artworkUrl,
                                title = row.title,
                                subtitle = row.subtitle,
                                isNowPlaying = row.id == state.nowPlayingId,
                                onClick = {
                                    when (row.kind) {
                                        MusicRow.Kind.ARTIST -> onOpenArtist(row)
                                        MusicRow.Kind.ALBUM, MusicRow.Kind.PLAYLIST -> onOpenAlbum(row)
                                        else -> onPlay(row)
                                    }
                                },
                                onLongClick = { if (row.kind == MusicRow.Kind.TRACK) onOptions(row) },
                            )
                        }
                    }
                }

                if (state.isHomeLoading && state.shelves.isEmpty()) {
                    item(key = "loading") {
                        Column {
                            SkeletonShelf()
                            SkeletonShelf()
                            SlowNetworkHint(isLoading = true)
                        }
                    }
                }

                if (state.homeError != null && state.shelves.isEmpty()) {
                    item(key = "error") {
                        StateError(
                            title = "Could not load music",
                            description = state.homeError,
                            onRetry = onRetryHome,
                        )
                    }
                }

                if (!state.isHomeLoading && state.homeError == null &&
                    state.shelves.isEmpty() && savedMusic.isEmpty() && playlists.isEmpty()
                ) {
                    item(key = "empty") {
                        StateEmpty(
                            title = "Nothing to play yet",
                            description = "Search for a track, album or playlist to start " +
                                "listening. What you play is saved here for next time.",
                            actionLabel = "Search music",
                            onAction = onOpenSearch,
                        )
                    }
                }

                if (!state.isHomeLoading && state.shelves.isNotEmpty()) {
                    item(key = "browse-all") {
                        BrowseAllRow(onClick = onOpenSearch)
                    }
                }
            }

            AnimatedVisibility(
                visible = showNowPlaying,
                enter = fadeIn(tween(AurisMotion.Standard)) +
                    slideInVertically(tween(AurisMotion.Standard, easing = AurisMotion.Ease)) { it / 2 },
                exit = fadeOut(tween(AurisMotion.Quick)) +
                    slideOutVertically(tween(AurisMotion.Quick)) { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = FloatingNavigationLayout.playerBottom),
            ) {
                NowPlayingStrip(
                    title = state.nowPlayingTitle ?: "Now playing",
                    artist = state.nowPlayingArtist,
                    artworkUrl = state.nowPlayingArtworkUrl,
                    isPlaying = state.isPlaying,
                    isLoading = state.isResolving || state.isBuffering,
                    onClick = { if (!state.isResolving) onOpenPlayer() },
                    onTogglePlayPause = onTogglePlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    canGoPrevious = !state.isResolving && state.canGoPrevious,
                    canGoNext = !state.isResolving && state.canGoNext,
                    modifier = Modifier,
                )
            }
        }
    }

    playlists.firstOrNull { it.id == selectedPlaylistId }?.let { playlist ->
        LocalPlaylistDetail(
            playlist = playlist,
            repository = playlistRepository,
            onBack = { selectedPlaylistId = null },
            onPlayTracks = onPlayTracks,
            onOptions = onOptions,
        )
    }
}

// ---- Pieces ------------------------------------------------------------------

@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = AurisOnSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Search YouTube Music",
                style = MaterialTheme.typography.bodyLarge,
                color = AurisOnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShelfHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = AurisOnSurface,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 12.dp),
    )
}

/**
 * One large square card, with the title under the art.
 *
 * Mirrors the reference shelves: art carries the recognition, text is secondary and clamped.
 */
@Composable
private fun MusicCard(
    artworkUrl: String?,
    title: String,
    subtitle: String?,
    isNowPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.pressFeedback(interactionSource)
            .width(CARD_WIDTH)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Box {
            MediaArtwork(
                imageUrl = nowPlayingArtworkUrl(artworkUrl),
                fallbackImageUrl = artworkUrl,
                contentDescription = title,
                modifier = Modifier.size(CARD_WIDTH),
                shape = ArtworkShape,
                placeholderIcon = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = AurisOnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
            )

            if (isNowPlaying) {
                Box(
                    modifier = Modifier
                        .padding(10.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(AurisAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "Now playing",
                        tint = AurisBackground,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = AurisOnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AurisOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The floating now-playing bar.
 *
 * Playing from a shelf does not navigate anywhere — the audio starts, and this is what proves
 * it and gives the way into the full player. Without it, tapping a card on Home looked like a
 * no-op until the next screen was opened.
 */
@Composable
internal fun NowPlayingStrip(
    title: String,
    artist: String?,
    artworkUrl: String?,
    isPlaying: Boolean,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(AurisRadii.artwork),
        // Opaque rather than the glass surface: the strip floats over shelf cards, and a
        // translucent panel let their titles bleed through it.
        color = AurisBackground,
        modifier = modifier.pressFeedback(interactionSource)
            .semantics { contentDescription = "Open now playing" }
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = AurisOnSurfaceVariant.copy(alpha = 0.15f),
                shape = RoundedCornerShape(AurisRadii.artwork),
            )
            .clickable(
                role = androidx.compose.ui.semantics.Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtwork(
                imageUrl = nowPlayingArtworkUrl(artworkUrl),
                fallbackImageUrl = artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .background(AurisTrack, ArtworkShapeSmall),
                shape = ArtworkShapeSmall,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = AurisOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist.isNullOrBlank()) {
                    Text(
                        text = artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisOnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onPrevious, enabled = canGoPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous track",
                    tint = if (canGoPrevious) AurisOnSurface else AurisOnSurfaceVariant.copy(alpha = 0.4f))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !isLoading,
                        onClick = onTogglePlayPause,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = AurisAccent, strokeWidth = 2.dp)
                } else Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = AurisOnSurface,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onNext, enabled = canGoNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next track",
                    tint = if (canGoNext) AurisOnSurface else AurisOnSurfaceVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun BrowseAllRow(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = AurisAccent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Browse all categories",
                style = MaterialTheme.typography.labelLarge,
                color = AurisOnSurface,
            )
        }
    }
}

/** 152dp keeps two cards and a sliver of the third visible on a 6.4" phone. */
private val CARD_WIDTH = 152.dp

/**
 * One local playlist entry under "Your playlists".
 *
 * Mirrors the sheet's SongPlaylistRow 56dp-art pattern (first-track artwork or the
 * neutral fallback, name, "N songs") minus its Add/Rename/Delete actions, leaving the
 * single 48dp-minimum tap target that opens the local detail.
 */
@Composable
private fun YourPlaylistRow(
    playlist: PlaylistEntity,
    repository: PlaylistRepository,
    onOpen: () -> Unit,
) {
    val tracks by repository.observeTracks(playlist.id).collectAsStateWithLifecycle(initialValue = emptyList())
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(tracks.firstOrNull()?.artworkUrl, null, Modifier.size(56.dp), shape = ArtworkShapeSmall)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tracks.size} songs", color = AurisOnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Local playlist detail, rendered as the same [MusicAlbumPage] the NavHost uses for
 * remote `Kind.PLAYLIST` rows (`isPlaylist = true`) so the header ("Playlist · N songs"),
 * Play button and track rows match. Tracks come from the local repository rather than
 * `collectionRows`, which cannot resolve a local playlist UUID. The page is a dialog:
 * Back dismisses it back onto Home, as with the remote MusicRowSaver path.
 */
@Composable
private fun LocalPlaylistDetail(
    playlist: PlaylistEntity,
    repository: PlaylistRepository,
    onBack: () -> Unit,
    onPlayTracks: (List<MusicRow>, Int) -> Unit,
    onOptions: (MusicRow) -> Unit,
) {
    val tracks by repository.observeTracks(playlist.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val rows = tracks.map { it.toMusicRow() }
    MusicAlbumPage(
        title = playlist.name,
        tracks = rows,
        loading = false,
        message = null,
        onBack = onBack,
        onPlayAll = { onPlayTracks(rows, 0) },
        onPlay = { index -> onPlayTracks(rows, index) },
        onOptions = onOptions,
        isPlaylist = true,
        artworkUrl = rows.firstOrNull()?.artworkUrl,
    )
}

private fun PlaylistTrackEntity.toMusicRow(): MusicRow = MusicRow(
    kind = MusicRow.Kind.TRACK,
    title = title,
    subtitle = artist,
    artworkUrl = artworkUrl,
    id = trackId,
)
