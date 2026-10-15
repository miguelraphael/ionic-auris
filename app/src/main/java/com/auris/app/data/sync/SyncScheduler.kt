package com.auris.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Enqueues the Supabase push/pull workers.
 *
 * Two distinct schedules, because the two workloads have different urgency:
 *  - [requestSync] — one-shot, network-gated. Fired by any meaningful library write so a
 *    favourite shows up on the user's other device within seconds.
 *  - schedulePeriodicSync — a floor under progress sync and remote pulls, which must still
 *    advance while the app is idle.
 *
 * Backoff starts at 30s and grows exponentially so a Supabase outage doesn't drain battery.
 */
class SyncScheduler(private val context: Context) {

    private val workManager get() = WorkManager.getInstance(context)

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Battery gating for the periodic floor only: expedited one-shots support
     * network and storage constraints alone, and building expedited work with
     * anything else throws IllegalArgumentException — which used to break every
     * library write that requested a sync.
     */
    private val periodicConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** Immediate, deduplicated push. [REPLACE] keeps the queue to one pending sync. */
    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<LibrarySyncWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_SYNC)
            .build()

        workManager.enqueueUniqueWork(
            UNIQUE_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /** Keep-alive pass for progress sync and remote pulls. */
    fun schedulePeriodicSync(intervalMinutes: Long = 30) {
        val request = PeriodicWorkRequestBuilder<LibrarySyncWorker>(
            intervalMinutes, TimeUnit.MINUTES,
        )
            .setConstraints(periodicConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(TAG_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelSync() {
        workManager.cancelUniqueWork(UNIQUE_ONE_SHOT)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    companion object {
        const val TAG_SYNC = "auris-library-sync"
        const val UNIQUE_ONE_SHOT = "auris-library-sync-now"
        const val UNIQUE_PERIODIC = "auris-library-sync-periodic"
    }
}