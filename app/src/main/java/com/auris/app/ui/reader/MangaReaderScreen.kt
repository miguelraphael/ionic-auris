package com.auris.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack

/**
 * Manga reader: vertically paged chapter images with a tap-to-reveal progress overlay.
 *
 * ### Why vertical
 * Japanese manga reads right-to-left and top-to-bottom, so vertical paging is the correct
 * orientation for the primary content. Horizontal paging with RTL feel is the alternative and is
 * added behind a preference later rather than guessed at now.
 *
 * ### Backdrop
 * Deliberately near-black rather than the page's own dominant colour. Manga pages are mostly
 * white, and adapting the chrome to a sampled page colour means the overlay changes colour on
 * every page turn — visually unstable and distracting while reading. A fixed dark backdrop also
 * guarantees the logo watermark has contrast regardless of page content, which is why the
 * logo variant is chosen explicitly here rather than left to the theme default.
 *
 * @param onBack invoked by the overlay's back affordance and by a back gesture at page 0.
 */
@Composable
fun MangaReaderScreen(
    state: MangaReaderUiState,
    onPageChanged: (Int) -> Unit,
    onToggleOverlay: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val pagerState = rememberPagerState(
        initialPage = state.currentPage,
        pageCount = { state.totalPages.coerceAtLeast(1) },
    )

    // Report page changes upward so the ViewModel's state and the pager's cannot drift.
    LaunchedEffect(pagerState) {
        snapshotFlowCompat(pagerState) { it.currentPage }.collect(onPageChanged)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(READER_BACKDROP),
    ) {
        when {
            state.isLoading -> ReaderLoading()
            state.pages.isEmpty() -> ReaderEmpty(state.error, onRetry)
            else -> VerticalPager(
                state = pagerState,
                // Reading consumes the whole screen: no system bars, no content padding.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                pageSpacing = 0.dp,
                // Beyond one screen either side keeps memory bounded on a 200-page chapter
                // without losing the feel of continuous scrolling.
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                val pageIndex = page
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Taps toggle the overlay rather than doing nothing; `indication = null`
                        // keeps the ripple out of the artwork.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleOverlay,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(state.pages[pageIndex].imageUrl ?: state.pages[pageIndex].url)
                            // Manga pages are tall; Fit keeps the whole page visible rather than
                            // filling the width and cropping the panels.
                            .crossfade(120)
                            .build(),
                        contentDescription = "Page ${pageIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Watermark. Uses the light-ink variant explicitly: the backdrop is fixed
                    // dark regardless of page content, so contrast is guaranteed.
                    if (state.isOverlayVisible) {
                        AurisLogo(
                            background = READER_BACKDROP,
                            size = 28.dp,
                            alpha = 0.22f,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(20.dp),
                        )
                    }
                }
            }
        }

        // Overlay
        AnimatedVisibility(
            visible = state.isOverlayVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(state, onBack)
        }

        AnimatedVisibility(
            visible = state.isOverlayVisible && state.totalPages > 0,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderProgressBar(state)
        }
    }
}

// ---- Bars -------------------------------------------------------------------

@Composable
private fun ReaderTopBar(
    state: MangaReaderUiState,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(READER_CHROME)
            .padding(
                top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding(),
                start = 20.dp,
                end = 20.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.chapterTitle.ifBlank { "Chapter" },
                style = MaterialTheme.typography.titleMedium,
                color = AurisOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.title,
                style = MaterialTheme.typography.bodySmall,
                color = AurisOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(12.dp))

        // The progress pill carries the count, so it is the only chrome the eye needs.
        Surface(
            shape = RoundedCornerShape(AurisRadii.chip),
            color = AurisSurface,
        ) {
            Text(
                text = state.progressLabel,
                style = MaterialTheme.typography.labelLarge,
                color = AurisAccent,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = "Back",
            style = MaterialTheme.typography.labelLarge,
            color = AurisOnSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(AurisRadii.button))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ReaderProgressBar(state: MangaReaderUiState) {
    val fraction = if (state.totalPages == 0) 0f else {
        (state.currentPage + 1f) / state.totalPages
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(READER_CHROME)
            .padding(
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
            ),
    ) {
        // Slim bar rather than a Material slider: a slider would imply the user can drag to seek,
        // which is not how paging works, and its thumb would invite a gesture that does nothing.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AurisTrack),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(AurisAccent),
            )
        }
    }
}

// ---- States -----------------------------------------------------------------

@Composable
private fun ReaderLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Logo watermark doubles as the loading state, so the screen is never blank chrome.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AurisLogo(background = READER_BACKDROP, size = 56.dp, alpha = 0.7f)
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = AurisAccent,
            )
        }
    }
}

@Composable
private fun ReaderEmpty(message: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            AurisLogo(background = READER_BACKDROP, size = 56.dp, alpha = 0.5f)
            Spacer(Modifier.height(20.dp))
            Text(
                text = message ?: "Nothing to read here",
                style = MaterialTheme.typography.bodyLarge,
                color = AurisOnSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(AurisRadii.button),
                color = AurisAccent,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRetry,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = READER_BACKDROP,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelLarge,
                        color = READER_BACKDROP,
                    )
                }
            }
        }
    }
}

// ---- Constants --------------------------------------------------------------

/**
 * Reader backdrop, fixed rather than sampled.
 *
 * Sampling the page would make the chrome change colour on every turn. Fixed also guarantees the
 * logo watermark and chrome text keep contrast whatever the artwork is.
 */
private val READER_BACKDROP = Color(0xFF0B0E14)

/** Chrome sits over artwork, so it is translucent rather than opaque. */
private val READER_CHROME = Color(0xE00B0E14)

/** Reads [PagerState.currentPage] as a flow without importing the snapshotFlow plumbing here. */
private fun snapshotFlowCompat(
    pagerState: androidx.compose.foundation.pager.PagerState,
    read: (androidx.compose.foundation.pager.PagerState) -> Int,
) = androidx.compose.runtime.snapshotFlow { read(pagerState) }