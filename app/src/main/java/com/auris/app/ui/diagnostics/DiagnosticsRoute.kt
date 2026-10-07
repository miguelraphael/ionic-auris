package com.auris.app.ui.diagnostics

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
import com.auris.app.diagnostics.AppStateCollector
import com.auris.app.diagnostics.AurisLogBuffer
import com.auris.app.diagnostics.LogExporter
import com.auris.app.ui.theme.AurisOnSurfaceVariant
import kotlinx.coroutines.launch

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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bufferedLines by remember { mutableIntStateOf(buffer.size()) }
    var droppedLines by remember { mutableIntStateOf(buffer.droppedCount()) }
    var isExporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

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
        modifier = modifier,
    )
}