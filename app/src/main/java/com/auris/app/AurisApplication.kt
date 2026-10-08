package com.auris.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
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
        registerExtensionHostServices()
        container = DefaultAppContainer(this)

        // Floor under progress sync and remote pulls. Individual writes call
        // SyncScheduler.requestSync() for immediate delivery.
        container.syncScheduler.schedulePeriodicSync()
    }

    val syncScheduler: SyncScheduler get() = container.syncScheduler

    /**
     * Registers the [Application] with Injekt so extensions can reach it.
     *
     * ### Why this is not optional
     * Aniyomi's source-api uses Injekt as its dependency-injection seam, and a real extension's
     * factory resolves `Injekt.get<Application>()` while building its configuration — typically
     * to obtain a `SharedPreferences` for its login or mirror preference.
     *
     * Without the registration the factory throws
     * `InjektionException: No registered instance or factory for type class android.app.Application`,
     * which surfaces as an extension that loads and then silently registers **zero sources**. That
     * is the same invisible failure the missing `AnimeFilter` types caused, one layer further
     * down, and it is only visible because the extension is real.
     *
     * Done in `onCreate` before any extension can load, since the loader reads this at
     * factory-instantiation time.
     */
    private fun registerExtensionHostServices() {
        runCatching {
            Injekt.addSingletonFactory<Application> { this@AurisApplication }
        }.onFailure { error ->
            // Non-fatal: an extension that needs Injekt will fail loudly on its own, and the
            // app must still boot for the ones that do not.
            Log.w(TAG, "Could not register Application with Injekt: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "AurisApplication"
    }
}