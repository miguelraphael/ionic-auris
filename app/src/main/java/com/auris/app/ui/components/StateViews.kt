package com.auris.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisAmber
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisRose
import com.auris.app.ui.theme.AurisSurfaceOpaque
import com.auris.app.ui.theme.AurisTrack
import com.auris.app.ui.theme.GlassEdge
import kotlinx.coroutines.delay

/**
 * Whether the device currently has a usable network.
 *
 * Defaults to `true` so previews and tests do not accidentally render the offline treatment;
 * the shell overrides it with the real [com.auris.app.core.network.NetworkMonitor] value.
 */
val LocalIsOnline = compositionLocalOf { true }

// ---- Shared actions ----------------------------------------------------------

/** The filled pill used by state cards, matching the app's other primary actions. */
@Composable
fun StateActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val shape = RoundedCornerShape(AurisRadii.button)
    Box(
        modifier = modifier
            .clip(shape)
            .then(
                if (filled) {
                    Modifier.background(AurisAccent)
                } else {
                    Modifier.border(1.dp, GlassEdge, shape)
                },
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) AurisBackground else AurisOnSurface,
        )
    }
}

/** A circular glyph that heads an empty, error or no-results state. */
@Composable
private fun StateGlyph(
    icon: ImageVector,
    tint: Color = AurisAccent,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

// ---- Empty / no results / error ---------------------------------------------

/**
 * The one empty state used across the app.
 *
 * Deliberately a state rather than a blank screen: a library with nothing in it, a source with
 * no entries and a search that matched nothing all look identical when the only thing on screen
 * is white space, and the user cannot tell whether the app is broken or simply new.
 */
@Composable
fun StateEmpty(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateGlyph(icon)
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = AurisOnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            StateActionButton(label = actionLabel, onClick = onAction)
        }
    }
}

/**
 * A search that ran and matched nothing.
 *
 * Kept separate from [StateEmpty] because the way out is different: an empty library is fixed by
 * adding something, while a search with no hits is fixed by changing or clearing the query.
 */
@Composable
fun StateNoResults(
    query: String,
    modifier: Modifier = Modifier,
    title: String = "No results for \u201C$query\u201D",
    description: String = "Try a different spelling, a shorter query, or clear the search.",
    actionLabel: String = "Clear search",
    onClear: (() -> Unit)? = null,
    icon: ImageVector = Icons.Filled.SearchOff,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateGlyph(icon, tint = AurisOnSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = AurisOnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onClear != null) {
            Spacer(Modifier.height(18.dp))
            StateActionButton(label = actionLabel, onClick = onClear, filled = false)
        }
    }
}

/**
 * A failed load, with an offline variant.
 *
 * When the device has no network the screen says so, instead of repeating "the source could not
 * be reached" and leaving the user to work out why. The retry action is kept because
 * connectivity often returns while the screen is still open.
 */
@Composable
fun StateError(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "Try again",
    offlineTitle: String = "You\u2019re offline",
    offlineDescription: String = "Check your connection. Anything you saved is still on this device.",
) {
    val offline = !LocalIsOnline.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StateGlyph(
            icon = if (offline) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
            tint = if (offline) AurisAmber else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (offline) offlineTitle else title,
            style = MaterialTheme.typography.titleLarge,
            color = AurisOnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (offline) offlineDescription else description,
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(18.dp))
            StateActionButton(label = retryLabel, onClick = onRetry)
        }
    }
}

// ---- Banners -----------------------------------------------------------------

/** Global connectivity strip. Sits inside the 56dp header reserve, so it overlaps no title. */
@Composable
fun OfflineBanner(
    modifier: Modifier = Modifier,
    message: String = "No internet \u2014 showing saved content",
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisSurfaceOpaque,
        border = BorderStroke(1.dp, AurisAmber.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = AurisAmber,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = AurisOnSurface,
            )
        }
    }
}

/** A confirmation: saved work, a completed install, a registered account. */
@Composable
fun SuccessBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisAccent.copy(alpha = 0.14f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = AurisAccent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = AurisOnSurface,
            )
        }
    }
}

/** A failure that is worth reporting without replacing the screen. */
@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisRose.copy(alpha = 0.14f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Shown after a request has been in flight long enough that the user will assume it hung.
 *
 * Skeletons answer "is it working?", not "is it stuck?". On a slow connection the shape is
 * identical, so this adds the missing sentence without replacing the skeleton.
 */
@Composable
fun SlowNetworkHint(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    delayMillis: Long = 3_500L,
    message: String = "This is taking longer than usual \u2014 the connection looks slow.",
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading) {
        visible = false
        if (isLoading) {
            delay(delayMillis)
            visible = true
        }
    }

    AnimatedVisibility(
        visible = isLoading && visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.5.dp,
                color = AurisAmber,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = AurisOnSurfaceVariant,
            )
        }
    }
}

// ---- Form validation ---------------------------------------------------------

/** The message under a field that failed validation. */
@Composable
fun InlineFieldError(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ---- Permission denied -------------------------------------------------------

/**
 * A runtime permission the user refused.
 *
 * Both actions are always offered: "Allow" re-asks while Android still permits a prompt, and
 * "Open settings" is the only route after the second refusal, when the system stops showing the
 * dialog. Hiding the second button would strand a user who denied twice.
 */
@Composable
fun PermissionDeniedCard(
    title: String,
    description: String,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.artwork),
        color = AurisAmber.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, AurisAmber.copy(alpha = 0.28f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.NotificationsOff,
                    contentDescription = null,
                    tint = AurisAmber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurisOnSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = AurisOnSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StateActionButton(label = "Allow", onClick = onAllow)
                StateActionButton(label = "Open settings", onClick = onOpenSettings, filled = false)
            }
        }
    }
}

// ---- Session expired ---------------------------------------------------------

/**
 * The refresh token is no longer accepted.
 *
 * Nothing local is thrown away: the library stays in Room and remains readable. The dialog is
 * dismissible because work saved offline still matters, and signing back in later is enough to
 * resume syncing.
 */
@Composable
fun SessionExpiredDialog(
    visible: Boolean,
    email: String?,
    onSignInAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AurisBackground,
        icon = {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = AurisAccent,
                modifier = Modifier.size(26.dp),
            )
        },
        title = {
            Text(
                text = "Session expired",
                style = MaterialTheme.typography.titleLarge,
                color = AurisOnSurface,
            )
        },
        text = {
            Text(
                text = buildString {
                    append("Sign in again to keep syncing")
                    if (!email.isNullOrBlank()) append(" as $email")
                    append(". Your library is still saved on this device, and nothing was lost.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onSignInAgain) {
                Text("Sign in again", color = AurisAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", color = AurisOnSurfaceVariant)
            }
        },
    )
}

// ---- Skeletons ---------------------------------------------------------------

/** Reads animation state during drawing so shimmer frames do not recompose placeholders. */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AurisRadii.chip),
) {
    val transition = rememberInfiniteTransition(label = "auris-skeleton")
    val x = transition.animateFloat(
        initialValue = -700f,
        targetValue = 1_400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
        ),
        label = "sweep",
    )
    val colors = remember { listOf(AurisTrack, GlassEdge, AurisTrack) }
    Box(
        modifier.clip(shape).drawBehind {
            val offset = x.value
            drawRect(
                brush = Brush.linearGradient(
                    colors = colors,
                    start = Offset(offset, 0f),
                    end = Offset(offset + 700f, 700f),
                ),
            )
        },
    )
}

/** A text-line placeholder: width as a fraction of the parent, with a fixed line height. */
@Composable
fun SkeletonLine(
    widthFraction: Float = 1f,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    SkeletonBlock(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height),
        shape = RoundedCornerShape(AurisRadii.chip),
    )
}

/** One poster in a grid, in the same 2:3 frame the real card uses. */
@Composable
private fun SkeletonPosterCard(modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(AurisRadii.artwork),
        )
        Spacer(Modifier.height(8.dp))
        SkeletonLine(widthFraction = 0.78f)
        Spacer(Modifier.height(6.dp))
        SkeletonLine(widthFraction = 0.48f, height = 12.dp)
    }
}

/**
 * A poster grid placeholder.
 *
 * Laid out as rows of weighted cards rather than a LazyVerticalGrid so it can be dropped inside
 * an existing grid item, a Column, or a full-screen Box without nesting a second scrollable.
 */
@Composable
fun SkeletonPosterGrid(
    modifier: Modifier = Modifier,
    rows: Int = 2,
    columns: Int = 2,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(columns) {
                    SkeletonPosterCard(Modifier.weight(1f))
                }
            }
        }
    }
}

/** A shelf placeholder: header line plus three square cards, matching the music home. */
@Composable
fun SkeletonShelf(
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
) {
    Column(modifier.fillMaxWidth()) {
        SkeletonLine(widthFraction = 0.36f, height = 18.dp, modifier = Modifier.padding(start = 20.dp))
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(3) {
                Column(Modifier.width(cardWidth)) {
                    SkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(AurisRadii.artwork),
                    )
                    Spacer(Modifier.height(8.dp))
                    SkeletonLine(widthFraction = 0.82f)
                    Spacer(Modifier.height(5.dp))
                    SkeletonLine(widthFraction = 0.5f, height = 12.dp)
                }
            }
        }
    }
}

/** Rows with a square thumbnail and two lines: searches, episodes and extensions. */
@Composable
fun SkeletonListRows(
    modifier: Modifier = Modifier,
    count: Int = 4,
    thumbnailSize: Dp = 52.dp,
    thumbnailShape: Shape = RoundedCornerShape(AurisRadii.artworkSmall),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(count) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBlock(Modifier.size(thumbnailSize), shape = thumbnailShape)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SkeletonLine(widthFraction = 0.72f)
                    Spacer(Modifier.height(7.dp))
                    SkeletonLine(widthFraction = 0.42f, height = 12.dp)
                }
            }
        }
    }
}

/** Full-width episode/chapter rows, matching the detail screen's unit cards. */
@Composable
fun SkeletonUnitRows(
    modifier: Modifier = Modifier,
    count: Int = 5,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(count) {
            SkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(AurisRadii.artworkSmall),
            )
        }
    }
}
