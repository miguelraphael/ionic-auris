package com.auris.app.ui.extensions

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.auris.app.R
import com.auris.app.source.repo.ExtensionListItem
import com.auris.app.source.repo.InstallProgress
import com.auris.app.source.repo.ExtensionRepo
import com.auris.app.ui.components.SectionHeader
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
import kotlin.math.roundToInt

/**
 * Extension management: browse a repository, install, remove, enable/disable.
 *
 * ### Design rules honoured here
 *  - **Dynamic background** — the artwork gradient follows the icon of the most recently
 *    interacted-with extension, exactly as the player follows the album art.
 *  - **Large rounded corners** — rows use the same `AurisRadii.artwork` as every other card.
 *  - **Floating navigation** — this screen is reached from Settings, not a top-level tab, so it
 *    draws no bottom pill and instead reserves [bottomPadding] for the system inset.
 */
@Composable
fun ExtensionsScreen(
    state: ExtensionsUiState,
    actions: ExtensionsUiActions,
    modifier: Modifier = Modifier,
    repo: ExtensionRepo = ExtensionRepo.DEFAULT,
    onOpenDiagnostics: () -> Unit = {},
    bottomPadding: androidx.compose.ui.unit.Dp = 32.dp,
) {
    // The gradient tracks whichever icon the user last touched, so the header carries a colour.
    val palette = rememberArtworkPaletteValue(artworkKey = state.focusIconKey)

    ArtworkGradientBackground(
        palette = palette,
        intensity = 0.9f,
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 56.dp,
                bottom = bottomPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                ExtensionsHeader(
                    repo = repo,
                    extensions = state.total,
                    installed = state.installed,
                    updates = state.updates,
                    isLoading = state.isLoading,
                    isNsfwVisible = state.showNsfw,
                    onToggleNsfw = actions.onToggleNsfw,
                    onRefresh = actions.onRefresh,
                    onEditRepo = actions.onEditRepo,
                    onOpenDiagnostics = onOpenDiagnostics,
                )
            }

            if (state.error != null) {
                item(key = "error") { RepoErrorCard(state.error, actions.onRetry) }
            }

            if (!state.isLoading && state.items.isEmpty() && state.error == null) {
                item(key = "empty") { EmptyCatalog(repo, actions.onRefresh) }
            }

            items(state.items, key = { it.remote.packageName }) { item ->
                ExtensionRow(
                    item = item,
                    stage = state.progress[item.remote.packageName],
                    onInstall = { actions.onInstall(item) },
                    onUninstall = { actions.onUninstall(item) },
                    onToggle = { actions.onSetEnabled(item, it) },
                    onFocus = { actions.onFocus(item) },
                )
            }
        }
    }
}

// ---- Header ------------------------------------------------------------------

@Composable
private fun ExtensionsHeader(
    repo: ExtensionRepo,
    extensions: Int,
    installed: Int,
    updates: Int,
    isLoading: Boolean,
    isNsfwVisible: Boolean,
    onToggleNsfw: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onEditRepo: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Extensions",
                    style = MaterialTheme.typography.displayLarge,
                    color = AurisOnSurface,
                )
                Text(
                    text = if (isLoading) {
                        "Loading ${repo.shortName ?: repo.name}…"
                    } else {
                        "$installed of $extensions installed" +
                            if (updates > 0) " · $updates update${if (updates == 1) "" else "s"}" else ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurisOnSurfaceVariant,
                )
            }

            HeaderIconButton(
                icon = Icons.Filled.Refresh,
                contentDescription = "Refresh",
                onClick = onRefresh,
                enabled = !isLoading,
            )
            Spacer(Modifier.width(8.dp))
            HeaderIconButton(
                icon = Icons.Filled.CloudDownload,
                contentDescription = "Change repository",
                onClick = onEditRepo,
            )
            Spacer(Modifier.width(8.dp))
            // Diagnostics lives here because Settings does not exist yet. When a Settings screen
            // lands this moves, and nothing else about the exporter changes.
            HeaderIconButton(
                icon = Icons.Filled.BugReport,
                contentDescription = "Diagnostics",
                onClick = onOpenDiagnostics,
            )
        }

        Spacer(Modifier.height(14.dp))

        // Source-count filter row: rounded chips, matching the app's chip radius.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                label = "All",
                selected = isNsfwVisible,
                onClick = { onToggleNsfw(!isNsfwVisible) },
            )
            FilterChip(
                label = "Installed",
                selected = false,
                onClick = { },
            )
            FilterChip(
                label = "Updates",
                selected = false,
                onClick = { },
                badge = updates.takeIf { it > 0 },
            )
        }

        Spacer(Modifier.height(4.dp))

        // Signatures are not verified yet. Stated plainly rather than implied safe.
        Text(
            text = "Extensions run third-party code. Repos are not signature-verified yet.",
            style = MaterialTheme.typography.labelMedium,
            color = AurisOnSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AurisSurface)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) AurisOnSurface else AurisOnSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int? = null,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = if (selected) AurisAccent.copy(alpha = 0.18f) else AurisTrack,
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) AurisAccent else AurisOnSurfaceVariant,
            )
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = badge.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = AurisAccent,
                )
            }
        }
    }
}

// ---- Row ---------------------------------------------------------------------

@Composable
private fun ExtensionRow(
    item: ExtensionListItem,
    stage: InstallProgress.Stage?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onFocus: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.artwork),
        color = AurisSurface.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AurisOutline.copy(alpha = 0.35f), RoundedCornerShape(AurisRadii.artwork)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Extension icon, using the same large corner radius as every artwork surface.
            AsyncImage(
                model = item.iconUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(AurisRadii.artworkSmall))
                    .background(AurisTrack),
            )

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.remote.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AurisOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(item.remote.versionName)
                        if (item.isInstalled) {
                            append(" · installed ")
                            append(item.installed?.versionName.orEmpty())
                        }
                        append(" · ${item.remote.sourceCount} ")
                        append(if (item.remote.sourceCount == 1) "source" else "sources")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AurisOnSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.remote.isNsfw) {
                    Spacer(Modifier.height(4.dp))
                    NsfwTag()
                }
            }

            Spacer(Modifier.width(10.dp))

            when {
                stage == InstallProgress.Stage.DOWNLOADING ||
                    stage == InstallProgress.Stage.INSTALLING ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = AurisAccent,
                    )

                stage == InstallProgress.Stage.FAILED ->
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = "Install failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )

                item.isInstalled -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.hasUpdate) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Update available",
                            tint = AurisAccent,
                            modifier = Modifier
                                .size(22.dp)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = onInstall,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Uninstall",
                        tint = AurisOnSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onUninstall,
                            ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Switch(
                        checked = item.isEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AurisBackground,
                            checkedTrackColor = AurisAccent,
                            uncheckedThumbColor = AurisOnSurfaceVariant,
                            uncheckedTrackColor = AurisTrack,
                            uncheckedBorderColor = AurisOutline,
                        ),
                    )
                }

                else -> InstallButton(
                    label = if (item.hasUpdate) "Update" else "Install",
                    onClick = {
                        onFocus()
                        onInstall()
                    },
                )
            }
        }
    }
}

@Composable
private fun InstallButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.button),
        color = AurisAccent,
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClick,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = AurisBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun NsfwTag() {
    Surface(shape = RoundedCornerShape(AurisRadii.chip), color = AurisTrack) {
        Text(
            text = "18+",
            style = MaterialTheme.typography.labelMedium,
            color = AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ---- States ------------------------------------------------------------------

@Composable
private fun RepoErrorCard(message: String, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.artwork),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Could not read the repository",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            InstallButton("Retry", onRetry)
        }
    }
}

@Composable
private fun EmptyCatalog(repo: ExtensionRepo, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No extensions found",
            style = MaterialTheme.typography.titleLarge,
            color = AurisOnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${repo.name} returned no usable entries. Check the URL in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(16.dp))
        InstallButton("Refresh", onRefresh)
    }
}