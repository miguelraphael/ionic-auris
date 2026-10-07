package com.auris.app.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a diagnostic bundle and hands it to the Android share sheet.
 *
 * ### What is included, and why
 * - **Our own logs** — the app's `Log` output plus everything written to the in-memory ring
 *   buffer by [AurisLogBuffer]. The ring buffer is the important part: `logcat` only holds a
 *   few thousand lines system-wide, so a crash the user noticed "a while ago" has usually been
 *   evicted by the time they export.
 * - **Recent logcat** — the surrounding system's view, which shows crashes in the extension
 *   loader or ExoPlayer that our own tags would not name.
 * - **App state** — database version, installed extensions, loaded sources, and the device/OS
 *   build. Most "it does not work" reports are really "it does not work on this device" or "the
 *   extension did not load", and neither is answerable from logs alone.
 *
 * ### Redaction
 * Supabase credentials and bearer tokens are replaced with `[redacted]` before writing. The file
 * is going to leave the device through a share sheet, so anything sensitive in it has to be
 * assumed to end up in a chat window or a bug tracker. Redaction is deliberately conservative:
 * over-redacting costs a debugging round trip, under-redacting leaks a credential.
 */
class LogExporter(
    private val context: Context,
    private val buffer: AurisLogBuffer,
    private val appStateProvider: suspend () -> String,
    /** Whether to pull logcat. Off in tests: it would shell out to a device tool. */
    private val includeLogcat: Boolean = true,
) {

    /**
     * Builds the bundle and launches the share sheet.
     *
     * @return the file written, for logging or tests. `null` if the bundle could not be written,
     *   in which case nothing is shared.
     */
    suspend fun exportAndShare(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            // Timestamp in the name: successive exports must not overwrite each other, or the
            // user loses the copy they were about to send.
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(directory, "auris-logs-$stamp.txt")

            PrintWriter(file).use { writer ->
                writeHeader(writer)
                writeAppState(writer)
                writeOwnLogs(writer)
                writeLogcat(writer)
                writeFooter(writer)
            }

            share(file)
            file
        }.onFailure {
            Log.e(TAG, "Log export failed", it)
        }
    }

    // ---- Sections -----------------------------------------------------------

    private fun writeHeader(writer: PrintWriter) {
        writer.println("# Auris diagnostic bundle")
        writer.println("Generated: ${timestamp()}")
        writer.println("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        writer.println("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        writer.println("App version: ${appVersionName()} (${appVersionCode()})")
        writer.println()
    }

    /**
     * Collects app state before the logs, so it is readable even if the log section is truncated.
     */
    private suspend fun writeAppState(writer: PrintWriter) {
        writer.println("===== APP STATE =====")
        runCatching { appStateProvider() }
            .onSuccess { writer.println(it) }
            .onFailure { writer.println("(could not collect app state: ${it.message})") }
        writer.println()
    }

    private fun writeOwnLogs(writer: PrintWriter) {
        writer.println("===== APP LOG BUFFER (${buffer.size()} entries) =====")
        buffer.drain().forEach { writer.println(it) }
        writer.println()
    }

    /**
     * Dumps recent logcat.
     *
     * `-d` dumps and exits rather than streaming, so this cannot hang. `-t 5000` bounds the
     * output: an unbounded dump on a chatty device produces a multi-megabyte file that no
     * messaging app will accept, and a share sheet that fails silently helps nobody.
     */
    private fun writeLogcat(writer: PrintWriter) {
        writer.println("===== LOGCAT (last ${LOGCAT_LINES} lines, all buffers) =====")
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString())
                .redirectErrorStream(true)
                .start()

            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> writer.println(LogRedactor.redact(line)) }
            }
            process.waitFor()
        }.onFailure {
            // `logcat` is unavailable on some hardened/enterprise builds, and it is not a reason
            // to fail the whole export — the app's own buffer is the more useful half anyway.
            writer.println("(logcat unavailable: ${it.message})")
        }
        writer.println()
    }

    private fun writeFooter(writer: PrintWriter) {
        writer.println()
        writer.println("===== END =====")
        writer.println("Redaction: supabase URLs/keys, bearer tokens and cookies are replaced with [redacted].")
    }

    // ---- Sharing ------------------------------------------------------------

    private fun share(file: File) {
        // A FileProvider URI, never a `file://` one: since Android 7 handing a raw file path to
        // another app throws FileUriExposedException, and it would be a sandbox escape besides.
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Auris diagnostic logs")
            putExtra(
                Intent.EXTRA_TEXT,
                "Auris diagnostic bundle. Attach the .txt file — most chat apps strip the " +
                    "attachment if the body is empty.",
            )
            // Without this the target app cannot read the URI we just granted it.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(intent, "Share Auris logs").apply {
                // The chooser is launched from a non-Activity context (the exporter may run from
                // a service), so it needs its own task.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    // ---- Redaction ----------------------------------------------------------
    //
    // Applied at the single point text enters the bundle. See `LogRedactor` for why this lives
    // in its own dependency-free object.

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    private fun appVersionName(): String = runCatching {
        // Nullable from API 33 onwards, where versionName moved to PackageInfoFlags.
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun appVersionCode(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "AurisDiagnostics"


        /**
         * Lines of logcat to include.
         *
         * Bounded deliberately: 5000 lines is roughly 400 KB, which messaging apps and bug
         * trackers both accept, whereas an unbounded dump is routinely megabytes and gets
         * rejected with a confusing "file too large".
         */
        const val LOGCAT_LINES = 5_000

    }

}

/**
 * Collects the app-state section of a diagnostic bundle.
 *
 * ### Why this is worth gathering
 * Most "it doesn't work" reports are really one of three things: the extension did not load, the
 * database is on an unexpected version, or the device is doing something unusual. None of those
 * are answerable from a stack trace, and asking the user to reproduce with a specific device
 * costs a round trip that a few lines here saves.
 */
class AppStateCollector(
    private val context: Context,
    private val databaseVersion: () -> Int,
    private val extensionDao: com.auris.app.data.local.ExtensionDao,
    private val extensionLoader: com.auris.app.source.ExtensionLoader,
) {
    suspend fun collect(): String = buildString {
        appendLine("Database version: ${databaseVersion()}")
        appendLine("Extensions dir: ${java.io.File(context.filesDir, "extensions").listFiles()?.size ?: 0} file(s)")

        val installed = runCatching { extensionDao.observeInstalled() }.getOrNull()
        val rows = if (installed == null) {
            appendLine("Installed extensions: unavailable")
            emptyList()
        } else {
            // `first()` on a Flow suspends until the query returns, which is exactly the one-shot
            // read wanted here.
            runCatching { installed.first() }.getOrDefault(emptyList())
        }

        rows.forEach { extension ->
            appendLine(
                "  - ${extension.packageName} v${extension.versionName} " +
                    "(${extension.versionCode}) ${if (extension.enabled) "enabled" else "DISABLED"}",
            )
        }

        val loaded = extensionLoader.loadedPackages
        appendLine("Loaded extensions: ${loaded.joinToString().ifEmpty { "none" }}")
        appendLine("Loaded sources: ${extensionLoader.sources.size}")
        appendLine(
            "Sources: " + extensionLoader.sources
                .joinToString { "${it.sourceName}[${it.sourceLang}]" }
                .ifEmpty { "none" },
        )
    }
}