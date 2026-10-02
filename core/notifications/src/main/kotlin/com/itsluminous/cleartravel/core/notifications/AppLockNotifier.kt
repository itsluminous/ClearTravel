package com.itsluminous.cleartravel.core.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-031: background jobs (flight polling, calendar sync, Drive uploads) need the
 * database, and the database key lives only in memory after an unlock. A job that
 * fires before any unlock in this process cannot run — it posts this single,
 * fixed-id "unlock to sync" nudge and no-ops; opening the app re-kicks the schedules.
 * Tapping opens the app's lock screen.
 *
 * ADR-043 (the nag fix): Android kills the process between runs, so every WorkManager
 * cold start used to re-post — and re-alert — the same notification, several times a
 * day. Now (1) only the FLIGHT poller may call this, and only when an imminent flight
 * makes the nudge worth it (the pure policy lives with the worker); (2) it posts
 * **at most once per process lifetime** ([posted], reset by [clear] on unlock), so a
 * dozen deferred runs in one process cost one notification; (3) it is quiet —
 * `PRIORITY_LOW` on the reminders channel and `setOnlyAlertOnce`, so even a re-post
 * from a NEW process (after another kill) replaces the entry silently instead of
 * ringing again.
 */
@Singleton
class AppLockNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val posted = AtomicBoolean(false)

        /** True when the nudge has already been posted in this process (and not cleared since). */
        val postedThisProcess: Boolean get() = posted.get()

        /**
         * Posts the nudge unless it is already up for this process. Returns true when a
         * notification was actually posted (permission granted, first time this process).
         */
        fun notifyUnlockToSync(): Boolean {
            if (!NotificationPermissions.canPost(context)) return false
            if (!posted.compareAndSet(false, true)) return false
            val launch =
                context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP) }
            val contentIntent =
                launch?.let {
                    PendingIntent.getActivity(
                        context,
                        NOTIFICATION_ID,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            val notification =
                NotificationCompat
                    .Builder(context, NotificationChannels.CHANNEL_REMINDERS)
                    .setSmallIcon(R.drawable.notifications_ic_stat_flight)
                    .setContentTitle(context.getString(R.string.notifications_unlock_to_sync_title))
                    .setContentText(context.getString(R.string.notifications_unlock_to_sync_text))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notifications_unlock_to_sync_text)))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            return true
        }

        /** Removes the nudge once the user has unlocked, and re-arms the once-per-process latch. */
        fun clear() {
            posted.set(false)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        companion object {
            const val NOTIFICATION_ID = 0x4C4F434B // "LOCK"
        }
    }
