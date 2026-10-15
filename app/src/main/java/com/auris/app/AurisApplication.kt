package com.auris.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import com.auris.app.data.sync.SyncScheduler

/**
 * Application entry point.
 *
 * Implements [Configuration.Provider] so WorkManager initialises on demand. We removed the
 * automatic `WorkManagerInitializer` from the manifest (see `AndroidManifest.xml`) because the
 * extension tests need the app to boot without a background scheduler; with on-demand
 * initialisation, the first `WorkManager.getInstance` call configures it.
 */
class AurisApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        registerExtensionHostServices()
        reloadInstalledExtensions()

        // Floor under progress sync and remote pulls. Individual writes call
        // SyncScheduler.requestSync() for immediate delivery.
        container.syncScheduler.schedulePeriodicSync()
    }

    val syncScheduler: SyncScheduler get() = container.syncScheduler

    /**
     * Registers the host services extensions resolve out of Injekt.
     *
     * Runs after [container] exists but before any extension can load (all
     * container fields are lazy, so construction loads nothing), since the
     * loader reads these at factory-instantiation time.
     *
     * ### Why each registration exists
     * - `Application`: a real extension's factory resolves it to obtain
     *   `SharedPreferences` for login/mirror settings. Without it the factory
     *   throws `InjektionException` and registers **zero sources**.
     * - `Json`: `keiyoushi.utils` — the shared toolkit most Aniyomi extensions
     *   are built on — resolves it in a static initializer. Without it the first
     *   catalogue fetch dies as `NoClassDefFoundError` on the OkHttp dispatcher
     *   and takes the whole process down (verified: AnimeOnsen popular fetch).
     */
    private fun registerExtensionHostServices() {
        runCatching {
            Injekt.addSingletonFactory<Application> { this@AurisApplication }
        }.onFailure { error ->
            // Non-fatal: an extension that needs Injekt will fail loudly on its own, and the
            // app must still boot for the ones that do not.
            Log.w(TAG, "Could not register Application with Injekt: ${error.message}")
        }
        runCatching {
            Injekt.addSingletonFactory<kotlinx.serialization.json.Json> { container.json }
        }.onFailure { error ->
            Log.w(TAG, "Could not register Json with Injekt: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "AurisApplication"
    }

    /** Survives longer than any screen; cancelled only with the process. */
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Re-loads installed, enabled extensions after a process restart.
     *
     * Installing loads the APK immediately, but nothing re-ran that step on the
     * next launch: the APKs sat in the extensions directory while the registry
     * stayed empty, so every library entry reported "extension not installed"
     * and no catalogue source could resolve. Loading here — off the main
     * thread, per-APK failures contained inside the loader — restores the
     * pre-restart state before any screen needs it. Disabled extensions stay
     * unloaded, matching the toggle's meaning.
     */
    private fun reloadInstalledExtensions() {
        initScope.launch {
            runCatching {
                val enabled = container.extensionRepository.enabled()
                    .first()
                    .map { it.packageName }
                    .toSet()
                val apks = container.extensionsDir
                    .listFiles { file -> file.isFile && file.extension == "apk" }
                    ?.filter { it.nameWithoutExtension in enabled }
                    .orEmpty()
                if (apks.isNotEmpty()) {
                    val loaded = container.extensionLoader.loadAll(apks)
                    Log.i(TAG, "Reloaded ${loaded.size} installed extension(s)")
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not reload installed extensions: ${error.message}")
            }
        }
    }
}