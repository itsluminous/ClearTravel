package com.itsluminous.cleartravel.core.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-031: background jobs (flight polling, calendar sync, Drive uploads) need the
 * database, and the database key lives only in memory after an unlock. A job that
 * fires before any unlock in this process cannot run — it posts this single,
 * fixed-id "unlock to sync" nudge (later posts replace it, never stack) and no-ops;
 * opening the app re-kicks the schedules. Tapping opens the app's lock screen.
 */
@Singleton
class AppLockNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notifyUnlockToSync() {
            if (!NotificationPermissions.canPost(context)) return
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
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }

        /** Removes the nudge once the user has unlocked. */
        fun clear() {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        companion object {
            const val NOTIFICATION_ID = 0x4C4F434B // "LOCK"
        }
    }
