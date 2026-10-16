package com.auris.app.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auris.app.diagnostics.LogExporter
import com.auris.app.ui.theme.AurisAccent
import com.auris.app.ui.theme.AurisBackground
import com.auris.app.ui.theme.AurisLogo
import com.auris.app.ui.theme.AurisOnSurface
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import com.auris.app.ui.theme.AurisOutline
import com.auris.app.ui.theme.AurisRadii
import com.auris.app.ui.theme.AurisSurface
import com.auris.app.ui.theme.AurisTrack
import kotlinx.coroutines.launch

/** Everything the diagnostics screen renders. */
data class DiagnosticsUiState(
    val bufferedLines: Int = 0,
    val droppedLines: Int = 0,
    val isExporting: Boolean = false,
    val message: String? = null,
    /** One line per client identity, from the unauthenticated stream-access probe. */
    val probeLines: List<String> = emptyList(),
    val isProbing: Boolean = false,
    /** One line per Invidious instance, from the reachability-and-playback probe. */
    val invidiousProbeLines: List<String> = emptyList(),
    val isInvidiousProbing: Boolean = false,
)

/**
 * Diagnostics screen: exports a log bundle through the share sheet.
 *
 * ### Why the buffer state is shown before exporting
 * A user who taps a button and immediately gets a share sheet has no way to tell whether
 * anything was captured. Showing the retained-line count and how many were dropped turns "it did
 * something" into "here is what it is about to send", which is also the moment a user notices
 * that the app has logged nothing and reports it.
 *
 * @param onGenerateLogs performs the export and share. Passed in rather than constructed here so
 *   this screen holds no Android dependencies beyond composition.
 * @param onProbeStreamAccess runs the unauthenticated `/player` probe against several client
 *   identities. Also injected, for the same reason — the probe needs an HTTP client and a
 *   dispatcher, neither of which belongs in a composable.
 * @param onCopyProbeResults copies the probe lines to the clipboard.
 * @param onProbeInvidious runs the Invidious probe: reachability of each instance, then a ranged
 *   GET on the audio URL it resolves, so "bytes arrived" is measured rather than assumed.
 * @param onCopyInvidiousResults copies those lines to the clipboard.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onGenerateLogs: () -> Unit,
    onBack: () -> Unit,
    onProbeStreamAccess: () -> Unit = {},
    onCopyProbeResults: () -> Unit = {},
    onProbeInvidious: () -> Unit = {},
    onCopyInvidiousResults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AurisBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 56.dp),
        ) {
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.displaySmall,
                color = AurisOnSurface,
            )
            Text(
                text = "Export logs for troubleshooting",
                style = MaterialTheme.typography.bodyMedium,
                color = AurisOnSurfaceVariant,
            )

            Spacer(Modifier.height(28.dp))

            // Summary card
            Surface(
                shape = RoundedCornerShape(AurisRadii.artwork),
                color = AurisSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AurisLogo(background = AurisBackground, size = 32.dp, alpha = 0.8f)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${state.bufferedLines} lines buffered",
                                style = MaterialTheme.typography.titleMedium,
                                color = AurisOnSurface,
                            )
                            Text(
                                text = if (state.droppedLines > 0) {
                                    // Dropped lines are stated rather than hidden: a truncated
                                    // log that looks complete is worse than a short one that
                                    // admits it.
                                    "${state.droppedLines} older lines dropped (buffer full)"
                                } else {
                                    "Nothing dropped"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.droppedLines > 0) {
                                    AurisAccent
                                } else {
                                    AurisOnSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Export button
            Surface(
                shape = RoundedCornerShape(AurisRadii.button),
                color = if (state.isExporting) AurisTrack else AurisAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !state.isExporting,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onGenerateLogs,
                    )
                    .padding(vertical = 16.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.isExporting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = AurisOnSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Collecting…",
                                style = MaterialTheme.typography.labelLarge,
                                color = AurisOnSurfaceVariant,
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Share,
                                contentDescription = null,
                                tint = AurisBackground,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Generate Logs",
                                style = MaterialTheme.typography.labelLarge,
                                color = AurisBackground,
                            )
                        }
                    }
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(AurisRadii.chip),
                    color = AurisSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AurisOnSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // Stream-access probe.
            //
            // Lives on this screen rather than a music one because it answers a question about
            // the *network*, not about a library or an account: whether YouTube Music will serve
            // audio to this device without signing in. It is a one-off investigation, not a
            // setting.
            Surface(
                shape = RoundedCornerShape(AurisRadii.artworkSmall),
                color = AurisSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Music stream probe",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurisOnSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Asks YouTube Music for audio without signing in, from this " +
                            "device's network, across every client identity. If Wi-Fi returns " +
                            "nothing, run it again on mobile data — the answer can differ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisOnSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(AurisRadii.button),
                            color = if (state.isProbing) AurisTrack else AurisAccent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    enabled = !state.isProbing,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onProbeStreamAccess,
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (state.isProbing) "Probing…" else "Run probe",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (state.isProbing) AurisOnSurfaceVariant else AurisBackground,
                                )
                            }
                        }

                        // Only offered once there is something to copy — copying an empty
                        // report would silently succeed and tell nobody anything.
                        if (state.probeLines.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(AurisRadii.button),
                                color = AurisTrack,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCopyProbeResults,
                                    )
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "Copy",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AurisOnSurface,
                                )
                            }
                        }
                    }

                    if (state.probeLines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(AurisRadii.chip),
                            color = AurisTrack,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                state.probeLines.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = AurisOnSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Invidious playback probe.
            //
            // Separate from the probe above because it answers the *opposite* question. That one
            // asks whether YouTube will talk to us directly (it will not). This one asks whether
            // the route that does work — a public instance in front of YouTube — reaches this
            // device, and the ranged GET is the part that matters: a `200` from the API says the
            // instance resolved a URL, not that this network can pull audio from it.
            Surface(
                shape = RoundedCornerShape(AurisRadii.artworkSmall),
                color = AurisSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Invidious playback probe",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurisOnSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Resolves audio through the public Invidious instances and then " +
                            "reads the first 64 KiB of the stream itself — a status code cannot " +
                            "tell you whether audio actually arrives. Run it on Wi-Fi, then again " +
                            "on mobile data; the answer can differ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisOnSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            shape = RoundedCornerShape(AurisRadii.button),
                            color = if (state.isInvidiousProbing) AurisTrack else AurisAccent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    enabled = !state.isInvidiousProbing,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onProbeInvidious,
                                )
                                .padding(vertical = 12.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (state.isInvidiousProbing) "Probing…" else "Run probe",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (state.isInvidiousProbing) {
                                        AurisOnSurfaceVariant
                                    } else {
                                        AurisBackground
                                    },
                                )
                            }
                        }

                        if (state.invidiousProbeLines.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(AurisRadii.button),
                                color = AurisTrack,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onCopyInvidiousResults,
                                    )
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "Copy",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AurisOnSurface,
                                )
                            }
                        }
                    }

                    if (state.invidiousProbeLines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(AurisRadii.chip),
                            color = AurisTrack,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                state.invidiousProbeLines.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = AurisOnSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // What the bundle contains, and the redaction notice.
            Surface(
                shape = RoundedCornerShape(AurisRadii.artworkSmall),
                color = AurisTrack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "The bundle contains",
                        style = MaterialTheme.typography.titleSmall,
                        color = AurisOnSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Device and Android build",
                        "App version, database version, installed extensions",
                        "Recent app log buffer",
                        "Last ${LogExporter.LOGCAT_LINES} lines of logcat",
                    ).forEach { item ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = AurisAccent,
                                modifier = Modifier.width(12.dp),
                            )
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                color = AurisOnSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "Supabase credentials, bearer tokens and cookies are replaced " +
                            "with [redacted] before the file is written. The file is shared " +
                            "from app storage, so nothing outside this app can read it except " +
                            "the app you share it to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AurisOnSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(AurisRadii.chip),
                color = AurisTrack,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    )
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Back",
                    style = MaterialTheme.typography.labelLarge,
                    color = AurisOnSurface,
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}