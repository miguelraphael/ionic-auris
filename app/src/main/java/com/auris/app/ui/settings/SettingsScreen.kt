package com.auris.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.unit.dp
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface

/**
 * Settings.
 *
 * ### Why security state is on this screen rather than buried
 * "Are the extensions I'm running actually the ones their authors published?" is the question a
 * user should be able to answer without leaving the app. Showing a per-extension verdict next to
 * the repository it came from makes the security model legible: what is verified against a
 * published key, what is only pinned, and what is unknown because it predates checking.
 *
 * It also makes the limits visible. A screen that simply said "extensions are verified" would be
 * lying whenever the repository publishes no signing key.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onRepoUrlChange: (String) -> Unit,
    onBeginRepoEdit: () -> Unit,
    onCancelRepoEdit: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    nsfwVisible: Boolean = false,
    onNsfwVisibleChange: (Boolean) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AurisBackground),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionTitle("Extension repository") }

            item {
                RepoSection(
                    state = state,
                    onRepoUrlChange = onRepoUrlChange,
                    onBeginRepoEdit = onBeginRepoEdit,
                    onCancelRepoEdit = onCancelRepoEdit,
                )
            }

            item {
                SettingRow(
                    title = "Manage extensions",
                    subtitle = "${state.extensions.size} installed",
                    onClick = onOpenExtensions,
                )
            }

            item { SectionTitle("Security") }

            item { SecuritySummary(state) }

            if (state.isEmpty) {
                item {
                    Text(
                        text = "No extensions installed yet, so there is nothing to verify.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurisOnSurfaceVariant,
                    )
                }
            } else {
                items(state.extensions.size) { index ->
                    val extension = state.extensions[index]
                    ExtensionSecurityRow(extension)
                }
            }

            item { SectionTitle("Content") }

            item {
                SettingRow(
                    title = "Show adult content",
                    subtitle = if (nsfwVisible) "Visible" else "Hidden",
                    trailing = {
                        Switch(checked = nsfwVisible, onCheckedChange = onNsfwVisibleChange)
                    },
                    onClick = { onNsfwVisibleChange(!nsfwVisible) },
                )
            }

            item { SectionTitle("Diagnostics") }

            item {
                SettingRow(
                    title = "Logs and app state",
                    subtitle = "Export redacted diagnostics",
                    onClick = onOpenDiagnostics,
                )
            }
        }

        state.message?.let { message ->
            Surface(
                shape = RoundedCornerShape(AurisRadii.chip),
                color = AurisSurface,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 20.dp, vertical = 48.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurisOnSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---- Repository --------------------------------------------------------------

@Composable
private fun RepoSection(
    state: SettingsUiState,
    onRepoUrlChange: (String) -> Unit,
    onBeginRepoEdit: () -> Unit,
    onCancelRepoEdit: () -> Unit,
) {
    Column {
        if (state.repoEditing) {
            var field by remember { mutableStateOf(TextFieldValue(state.repoUrl)) }

            Surface(
                shape = RoundedCornerShape(AurisRadii.button),
                color = AurisSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { value ->
                        field = value
                        onRepoUrlChange(value.text)
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = AurisOnSurface),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton(text = "Save", onClick = onBeginRepoEdit)
                SmallButton(text = "Cancel", onClick = onCancelRepoEdit)
            }
        } else {
            SettingRow(
                title = state.repoName,
                subtitle = state.repoUrl,
                onClick = onBeginRepoEdit,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Stated rather than implied. A repository with no published key cannot produce a
        // verified verdict, and a user deserves to know that before trusting the list.
        InfoBanner(
            text = if (state.repoHasNoKey) {
                "This repository publishes no signing key. Extensions are pinned to the key they " +
                    "were first installed with, which detects a later swap but cannot confirm the " +
                    "publisher."
            } else {
                "Extensions are checked against this repository's published signing key."
            },
            isWarning = state.repoHasNoKey,
        )
    }
}

// ---- Security ----------------------------------------------------------------

@Composable
private fun SecuritySummary(state: SettingsUiState) {
    Column {
        if (state.extensions.isEmpty()) {
            Text(
                text = "Nothing installed yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )
            return@Column
        }

        CountRow(label = "Verified against repository key", count = state.verifiedCount)
        CountRow(label = "Pinned on first install", count = state.pinnedCount)
        CountRow(label = "Installed before verification", count = state.unrecordedCount)
    }
}

@Composable
private fun CountRow(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AurisOnSurfaceVariant,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = AurisOnSurface,
        )
    }
}

@Composable
private fun ExtensionSecurityRow(extension: ExtensionSecurity) {
    val (icon, tint, label) = when (extension.trust) {
        ExtensionTrust.VERIFIED ->
            Triple(Icons.Filled.CheckCircle, AurisAccent, "Verified")

        // Deliberately not styled as a success. Pinned means "unchanged since first install",
        // which is weaker and must not read as a clean bill of health.
        ExtensionTrust.PINNED ->
            Triple(Icons.Filled.Lock, AurisOnSurfaceVariant, "Pinned")

        ExtensionTrust.UNRECORDED ->
            Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "Not verified")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = extension.label,
                style = MaterialTheme.typography.bodyLarge,
                color = AurisOnSurface,
            )
            Text(
                text = extension.fingerprintShort ?: "no fingerprint recorded",
                style = MaterialTheme.typography.bodySmall,
                color = AurisOnSurfaceVariant,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

// ---- Shared pieces -----------------------------------------------------------

@Composable
private fun SettingRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AurisOnSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AurisOnSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = AurisAccent,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun InfoBanner(text: String, isWarning: Boolean) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = if (isWarning) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        } else {
            AurisSurface.copy(alpha = 0.7f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isWarning) MaterialTheme.colorScheme.error else AurisOnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(AurisRadii.chip),
        color = AurisAccent,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = AurisBackground,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}
