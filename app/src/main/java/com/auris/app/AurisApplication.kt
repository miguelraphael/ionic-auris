package com.auris.app

import android.app.Application
import androidx.work.Configuration
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

        // Floor under progress sync and remote pulls. Individual writes call
        // SyncScheduler.requestSync() for immediate delivery.
        container.syncScheduler.schedulePeriodicSync()
    }

    val syncScheduler: SyncScheduler get() = container.syncScheduler
}