package com.auris.app.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.auris.app.core.model.MediaType
import com.auris.app.source.SourceUnit
import com.auris.app.ui.theme.ArtworkGradientBackground
import com.auris.app.ui.theme.ArtworkPalette
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisOutline
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack
import com.auris.app.ui.theme.rememberArtworkPaletteValue

/**
 * Everything the detail screen renders.
 *
 * ### Two data sources, two load states
 * [kitsu] (metadata) and [units] (episode list) arrive independently: Kitsu is a network call
 * that can fail or be slow, while units come from the extension. Keeping them separate means a
 * Kitsu outage degrades the header only, and an extension that has not answered yet leaves the
 * metadata fully visible. One combined loading flag would blank the whole screen for whichever
 * is slower.
 */
data class MediaDetailUiState(
    val mediaType: MediaType = MediaType.ANIME,
    val title: String = "",
    val originalTitle: String? = null,
    val synopsis: String? = null,
    val posterUrl: String? = null,
    /** Wide 4.2:1 artwork; drives the background gradient. Null falls back to the poster. */
    val coverUrl: String? = null,
    val genres: List<String> = emptyList(),
    val averageRating: Double? = null,
    val status: String? = null,
    val subtype: String? = null,
    val totalUnits: Int? = null,
    /** True while the Kitsu lookup is in flight. */
    val isMetadataLoading: Boolean = false,
    /** True while the extension is fetching episodes/chapters. */
    val isUnitsLoading: Boolean = false,
    /** Kitsu is reachable but produced nothing. Distinct from "still looking". */
    val metadataUnavailable: Boolean = false,
    val units: List<SourceUnit> = emptyList(),
    val watchedUnitIndices: Set<Int> = emptySet(),
    val currentUnitIndex: Int = 0,
    /** True while a tapped episode's stream is being resolved before playback can start. */
    val isResolvingStream: Boolean = false,
    /** Transient one-shot message, e.g. "no playable stream for this episode". */
    val message: String? = null,
    val error: String? = null,
) {
    /** The artwork whose colours drive the gradient: wide cover first, then poster. */
    val gradientArtworkUrl: String? get() = coverUrl ?: posterUrl
}

/**
 * Media detail: Kitsu metadata up top, extension units below.
 *
 * ### Design rules
 * - **Dynamic background** — the gradient follows the *wide cover*, not the poster. A 3:4 poster
 *   cropped to fill a phone screen shows a small central patch of colour, whereas the 4.2:1
 *   cover is composed to fill exactly that shape, so the sampled palette is actually
 *   representative. Falls back to the poster, then to no gradient at all.
 * - **Prominent rounded corners** — the hero uses [AurisRadii.artworkLarge]; unit cards use
 *   [AurisRadii.artworkSmall].
 * - **Clean typography** — the title is the only place weight is spent; subtitles, ratings and
 *   counts are [AurisOnSurfaceVariant] at lower weights so the hierarchy survives a glance.
 *
 * @param onUnitClick receives the tapped unit; the caller owns playback/navigation.
 */
@Composable
fun MediaDetailScreen(
    state: MediaDetailUiState,
    onUnitClick: (SourceUnit) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 32.dp,
) {
    val palette = rememberArtworkPaletteValue(artworkKey = state.gradientArtworkUrl)

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.85f,
        // Fades out earlier than the player's: the unit list is long and a gradient reaching
        // 55% of the viewport would tint every card behind it.
        fadeEndFraction = 0.42f,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "hero") {
                DetailHero(
                    state = state,
                    palette = palette,
                )
            }

            item(key = "units-header") {
                UnitListHeader(state)
            }

            if (state.isUnitsLoading && state.units.isEmpty()) {
                item(key = "units-loading") { LoadingBlock("Loading episodes…") }
            }

            state.message?.let { message ->
                item(key = "message") { TransientNotice(message) }
            }

            if (state.error != null) {
                item(key = "error") { DetailError(state.error, onRetry) }
            }

            if (!state.isUnitsLoading && state.units.isEmpty() && state.error == null) {
                item(key = "units-empty") { EmptyBlock("No episodes listed for this title") }
            }

            items(state.units, key = { it.url }) { unit ->
                UnitRow(
                    unit = unit,
                    isWatched = unit.index in state.watchedUnitIndices,
                    isCurrent = unit.index == state.currentUnitIndex,
                    // Tapping an episode whose stream is still resolving does nothing: a second
                    // tap would launch a second resolution and the first stream would be
                    // orphaned once the second completed.
                    enabled = !state.isResolvingStream,
                    onClick = { onUnitClick(unit) },
                )
            }
        }
    }
}

// ---- Hero -------------------------------------------------------------------

@Composable
private fun DetailHero(
    state: MediaDetailUiState,
    palette: ArtworkPalette,
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(WIDE_ASPECT),
        ) {
            // The wide cover is the composition the artwork was designed for, so it is shown at
            // full bleed here rather than as a poster-shaped card.
            AsyncImage(
                model = state.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Scrim. Two stops rather than a single flat overlay, so the title text at the
            // bottom is legible against a bright cover without dimming the artwork itself more
            // than necessary.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.45f to Color.Transparent,
                            1f to AurisBackground.copy(alpha = 0.92f),
                        ),
                    ),
            )

            if (state.isMetadataLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                    color = AurisAccent,
                )
            }
        }

        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.displaySmall,
                color = AurisOnSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            if (!state.originalTitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // Source-language title. Kept small and quiet: it is context for someone
                    // cross-referencing, not a second headline.
                    text = state.originalTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurisOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            MetadataChips(state)

            if (!state.synopsis.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Synopsis(state.synopsis)
            }

            if (state.metadataUnavailable) {
                Spacer(Modifier.height(12.dp))
                MetadataUnavailableNote()
            }
        }
    }
}

/**
 * Rating, status and episode count as chips.
 *
 * Only shown for values that exist — an empty chip would imply a zero rating or a zero episode
 * count, both of which are different claims from "unknown".
 */
@Composable
private fun MetadataChips(state: MediaDetailUiState) {
    val chips = buildList {
        state.averageRating?.let { add("${"%.1f".format(it)} ★") }
        state.status?.takeIf { it.isNotBlank() }?.let { add(it.replace('_', ' ')) }
        state.subtype?.takeIf { it.isNotBlank() }?.let { add(it) }
        state.totalUnits?.let {
            add(if (state.isManga()) "$it chapters" else "$it episodes")
        }
    }

    if (chips.isEmpty() && state.genres.isEmpty()) return

    Column {
        if (chips.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { DetailChip(it) }
            }
        }

        if (state.genres.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // LazyRow, not FlowRow: no dependency on an experimental layout, and the list is
            // naturally short.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.genres) { GenreChip(it) }
            }
        }
    }
}

@Composable
private fun DetailChip(label: String) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisTrack,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = AurisOnSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun GenreChip(label: String) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisAccent.copy(alpha = 0.14f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AurisAccent,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/**
 * Synopsis with a show-more toggle.
 *
 * Collapsed to a few lines because a full AniList/Kitsu synopsis runs to several paragraphs and
 * would push the episode list — the reason most people open this screen — entirely off-screen.
 */
@Composable
private fun Synopsis(synopsis: String) {
    var expanded by remember(synopsis) { mutableStateOf(false) }
    val isLong = synopsis.length > SYNOPSIS_COLLAPSED_CHARS

    Column(Modifier.animateContentSize()) {
        Text(
            text = synopsis,
            style = MaterialTheme.typography.bodyLarge,
            color = AurisOnSurfaceVariant,
            maxLines = if (expanded || !isLong) Int.MAX_VALUE else SYNOPSIS_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )

        if (isLong) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { expanded = !expanded },
                ),
            ) {
                Text(
                    text = if (expanded) "Less" else "More",
                    style = MaterialTheme.typography.labelLarge,
                    color = AurisAccent,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = AurisAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MetadataUnavailableNote() {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisSurface.copy(alpha = 0.6f),
    ) {
        Text(
            // Stated, not hidden. A user who sees a bare title cannot tell whether the lookup
            // failed, has not finished, or genuinely found nothing.
            text = "No catalogue data available — showing source details only.",
            style = MaterialTheme.typography.labelMedium,
            color = AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

// ---- Units ------------------------------------------------------------------

@Composable
private fun UnitListHeader(state: MediaDetailUiState) {
    val label = if (state.isManga()) "Chapters" else "Episodes"

    Column(Modifier.padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.totalUnits != null) {
                    "$label (${state.units.size} of ${state.totalUnits})"
                } else {
                    "$label (${state.units.size})"
                },
                style = MaterialTheme.typography.titleLarge,
                color = AurisOnSurface,
            )
            Spacer(Modifier.width(10.dp))
            if (state.isUnitsLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = AurisAccent,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UnitRow(
    unit: SourceUnit,
    isWatched: Boolean,
    isCurrent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(AurisRadii.artworkSmall))
            .background(if (isCurrent) AurisAccent.copy(alpha = 0.10f) else AurisSurface.copy(alpha = 0.45f))
            .border(
                width = 1.dp,
                color = if (isCurrent) AurisAccent.copy(alpha = 0.5f) else AurisOutline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(AurisRadii.artworkSmall),
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail is optional: many sources provide no episode art, so the row must not
        // reserve an empty box when the URL is absent.
        if (unit.thumbnailUrl != null) {
            AsyncImage(
                model = unit.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(AurisRadii.artworkSmall))
                    .background(AurisTrack),
            )
            Spacer(Modifier.width(12.dp))
        } else {
            // Index badge takes the thumbnail's place, so every row is still tappable at the
            // same height and the numbering stays visible at a glance.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(AurisRadii.chip))
                    .background(AurisTrack),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = unit.index.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = AurisOnSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = unit.title.ifBlank { "Unit ${unit.index}" },
                style = MaterialTheme.typography.titleSmall,
                color = AurisOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (unit.uploadTimestamp > 0L) {
                Text(
                    text = formatTimestamp(unit.uploadTimestamp),
                    style = MaterialTheme.typography.labelMedium,
                    color = AurisOnSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        if (isWatched) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Watched",
                tint = AurisAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** "2 days ago". Falls back to nothing when the value is 0, which means "unknown". */
private fun formatTimestamp(epochMillis: Long): String {
    val days = (System.currentTimeMillis() - epochMillis) / 86_400_000L
    return when {
        days < 0 -> ""
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 365 -> "${days / 7} weeks ago"
        else -> "${days / 365} years ago"
    }
}

// ---- States -----------------------------------------------------------------

@Composable
private fun LoadingBlock(label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AurisOnSurfaceVariant)
    }
}

@Composable
private fun EmptyBlock(label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AurisOnSurfaceVariant)
    }
}

/** A non-fatal notice: the title loaded fine, one thing about it did not. */
@Composable
private fun TransientNotice(message: String) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.artworkSmall),
        color = AurisSurface.copy(alpha = 0.8f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun DetailError(message: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.artworkSmall),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(AurisRadii.button),
                color = AurisAccent,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onRetry,
                ),
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelLarge,
                    color = AurisBackground,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ---- Helpers ----------------------------------------------------------------

/**
 * Whether this is a chapter list rather than an episode list.
 *
 * Drives the wording only. Under Anikku the underlying extension call is the same either way —
 * `getEpisodeList` serves both — so the distinction is presentation, which is exactly why it
 * lives here rather than in the adapter.
 */
private fun MediaDetailUiState.isManga(): Boolean = mediaType == MediaType.MANGA

private const val WIDE_ASPECT = 16f / 9f
private const val SYNOPSIS_COLLAPSED_LINES = 4
private const val SYNOPSIS_COLLAPSED_CHARS = 280