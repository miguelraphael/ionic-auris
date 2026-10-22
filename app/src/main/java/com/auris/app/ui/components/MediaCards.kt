package com.auris.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.auris.app.ui.theme.ArtworkShape
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.ArtworkShapeSmall

/**
 * Rounded artwork surface shared by grid thumbnails, posters, covers and the player's
 * album art. The single 28dp radius from [AurisRadii.artwork] is applied everywhere so
 * the app reads as one system.
 */
@Composable
fun MediaArtwork(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    contentScale: ContentScale = ContentScale.Crop,
    /** Dark scrim over the artwork so a progress overlay or title stays readable. */
    scrim: Float = 0f,
    placeholderIcon: (@Composable () -> Unit)? = null,
) {
    val progress by animateFloatAsState(
        targetValue = scrim.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "artworkScrim",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF1F2430))
            .then(
                if (progress > 0f) {
                    Modifier.drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = progress))
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    // `hqdefault` is 480×360: a 16:9 frame letterboxed into a 4:3 canvas. Crop
                    // alone keeps those black bands (they are image content, not padding), so
                    // the player's square art showed thick bars top and bottom. Zooming by
                    // 4/3 removes exactly the bands; every other variant is untouched.
                    .then(
                        if (imageUrl.contains("hqdefault")) {
                            Modifier.graphicsLayer {
                                scaleX = HQ_DEFAULT_CONTENT_RATIO
                                scaleY = HQ_DEFAULT_CONTENT_RATIO
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription?.let { this.contentDescription = it } },
            )
        } else {
            placeholderIcon?.invoke()
        }
    }
}

/** 480×360 canvas around a 480×270 frame: 360 / 270. */
private const val HQ_DEFAULT_CONTENT_RATIO = 4f / 3f

/**
 * Standard library / discover grid cell: square rounded art with a title, subtitle and an
 * optional progress bar pinned to the bottom of the art.
 *
 * Only the artwork is clipped to [ArtworkShape] (inside [MediaArtwork]). The card body is left
 * unclipped on purpose: clipping the whole column rounded the title and subtitle off at their
 * corners, which is what made long titles look cut off at the edges.
 */
@Composable
fun MediaGridCard(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box {
            MediaArtwork(
                imageUrl = imageUrl,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
                ) {
                    AurisSlider(
                        progress = progress,
                        onProgressChange = {},
                        trackHeight = 4.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AurisOnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * Episode / chapter row. Uses a large rounded thumbnail and a rounded card body, per the
 * "prominent rounded corners on episode cards" rule.
 */
@Composable
fun EpisodeCard(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    episodeLabel: String? = null,
    subtitle: String? = null,
    progress: Float = 0f,
    isWatched: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(ArtworkShape)
            .background(Color(0xFF1D222D))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(
            imageUrl = imageUrl,
            contentDescription = title,
            shape = ArtworkShapeSmall,
            modifier = Modifier
                .size(width = 132.dp, height = 76.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
        ) {
            Text(
                text = episodeLabel ?: title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isWatched) AurisOnSurfaceVariant else AurisOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurisOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (progress > 0f) {
                AurisSlider(
                    progress = progress,
                    onProgressChange = {},
                    trackHeight = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

/** Horizontal shelf header used across Home. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = AurisOnSurface,
        )
        action?.invoke()
    }
}