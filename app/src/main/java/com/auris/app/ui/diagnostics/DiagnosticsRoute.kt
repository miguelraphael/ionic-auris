package com.auris.app.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auris.app.data.remote.music.InvidiousProbe
import com.auris.app.data.remote.music.MusicStreamProbe
import com.auris.app.diagnostics.AppStateCollector
import com.auris.app.diagnostics.AurisLogBuffer
import com.auris.app.diagnostics.LogExporter
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Wires [DiagnosticsScreen] to the exporter.
 *
 * Holds the export coroutine and its UI flags, which is all the screen needs beyond the exporter
 * itself — so the screen stays previewable and free of Android dependencies.
 */
@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    buffer: AurisLogBuffer = AurisLogBuffer.Global,
    exporterFactory: (Context, AurisLogBuffer) -> LogExporter = { ctx, buf ->
        LogExporter(ctx, buf, appStateProvider = { "" })
    },
    appStateCollector: AppStateCollector? = null,
    probeFactory: (Context) -> MusicStreamProbe = { MusicStreamProbe(probeHttpClient) },
    invidiousProbeFactory: (Context) -> InvidiousProbe = { InvidiousProbe(invidiousProbeHttpClient) },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bufferedLines by remember { mutableIntStateOf(buffer.size()) }
    var droppedLines by remember { mutableIntStateOf(buffer.droppedCount()) }
    var isExporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isProbing by remember { mutableStateOf(false) }
    var probeLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var isInvidiousProbing by remember { mutableStateOf(false) }
    var invidiousProbeLines by remember { mutableStateOf<List<String>>(emptyList()) }

    // Counts are re-read after an export, because logging during the export (which there
    // certainly is — the exporter itself logs) changes them.
    LaunchedEffect(isExporting) {
        if (!isExporting) return@LaunchedEffect
        bufferedLines = buffer.size()
        droppedLines = buffer.droppedCount()
    }

    DiagnosticsScreen(
        state = DiagnosticsUiState(
            bufferedLines = bufferedLines,
            droppedLines = droppedLines,
            isExporting = isExporting,
            message = message,
            probeLines = probeLines,
            isProbing = isProbing,
            invidiousProbeLines = invidiousProbeLines,
            isInvidiousProbing = isInvidiousProbing,
        ),
        onGenerateLogs = {
            scope.launch {
                isExporting = true
                message = null

                (appStateCollector?.let { collector ->
                    LogExporter(context, buffer, collector::collect)
                } ?: exporterFactory(context, buffer)).exportAndShare()
                    .onSuccess { file ->
                        message = "Shared ${file.name} (${file.length() / 1024} KB)"
                    }
                    .onFailure { error ->
                        message = "Could not export logs: ${error.message}"
                    }

                isExporting = false
                bufferedLines = buffer.size()
                droppedLines = buffer.droppedCount()
            }
        },
        onBack = onBack,
        onProbeStreamAccess = {
            scope.launch {
                isProbing = true
                probeLines = emptyList()
                message = null

                runCatching {
                    // Collected on the main dispatcher, so each line lands on the UI as it
                    // arrives without the probe needing to know about composition.
                    probeFactory(context).probe().collect { result ->
                        probeLines = probeLines + result.report()
                    }
                }.onFailure { error ->
                    probeLines = probeLines + "probe failed: ${error.message ?: error::class.java.simpleName}"
                }

                isProbing = false
            }
        },
        onCopyProbeResults = {
            val report = probeLines.joinToString("\n")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Auris music probe", report))
            message = "Probe results copied to the clipboard"
            // Anchored by a marker so the start of the report is easy to find in the log bundle.
            android.util.Log.i("AurisMusicProbe", "report:\n$report")
        },
        onProbeInvidious = {
            scope.launch {
                isInvidiousProbing = true
                invidiousProbeLines = emptyList()
                message = null

                runCatching {
                    invidiousProbeFactory(context).probe().collect { result ->
                        invidiousProbeLines = invidiousProbeLines + result.report()
                    }
                }.onFailure { error ->
                    invidiousProbeLines = invidiousProbeLines +
                        "probe failed: ${error.message ?: error::class.java.simpleName}"
                }

                isInvidiousProbing = false
            }
        },
        onCopyInvidiousResults = {
            val report = invidiousProbeLines.joinToString("\n")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Auris invidious probe", report))
            message = "Probe results copied to the clipboard"
            android.util.Log.i("AurisInvidiousProbe", "report:\n$report")
        },
        modifier = modifier,
    )
}

/**
 * HTTP client for the stream probe.
 *
 * Separate from the app's shared client on purpose: a probe is a one-off diagnostic, and a
 * request that hangs there should not occupy a pooled connection the library and sync paths
 * expect to reuse.
 */
private val probeHttpClient: OkHttpClient by lazy { OkHttpClient() }

/**
 * HTTP client for the Invidious probe.
 *
 * Separate from [probeHttpClient] because this one makes a different *kind* of call: a ranged
 * download that follows a cross-host CDN redirect. Public instances are frequently slow, and the
 * stream leg needs longer than a default 10-second read timeout to be a fair test rather than a
 * timeout that looks like a refusal.
 */
private val invidiousProbeHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}