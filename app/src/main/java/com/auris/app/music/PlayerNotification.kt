package com.auris.app.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
/**
 * Notification *channel* setup for background playback.
 *
 * ### Why media3's own provider builds the notification
 * The transport buttons, the foreground-service lifecycle and the media-style notification are
 * all handled internally by `DefaultMediaNotificationProvider`. Hand-rolling a
 * `NotificationCompat` build means reimplementing action pending-intents against
 * `SessionCommands` constants that moved between media3 releases, and the failure mode is a
 * notification whose buttons silently do nothing.
 *
 * So this only owns the parts the app actually needs to control: the **channel** (stable id, so
 * it survives an upgrade and keeps the user's per-channel preferences) and the small icon.
 */
object PlayerNotification {

    /**
     * Channel id.
     *
     * Stable and hand-chosen rather than derived from the service class, because changing it
     * creates a *new* channel — Android would keep the old one and the user would see two
     * "Playback" entries after an upgrade.
     */
    const val CHANNEL_ID = "auris_playback"

    /** Notification id. Stable so a track change updates rather than stacking. */
    const val NOTIFICATION_ID = 0x4155 // "AU"

    /**
     * Creates the playback channel. Idempotent — `createNotificationChannel` on an existing id
     * is a no-op that preserves the user's existing settings, so this is safe on every start.
     */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playback",
                // LOW: a media notification is persistent *while playing* by design. Higher
                // importance would make every track change buzz and heads-up announce itself,
                // which is exactly what music apps must not do.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows the currently playing track and playback controls"
                // No dot on the launcher icon: the notification is transient, and a persistent
                // badge implies something is wrong when a track is simply playing.
                setShowBadge(false)
            },
        )
    }

    /** The channel used for the foreground-service notification. */
    fun notificationId(): Int = NOTIFICATION_ID
}