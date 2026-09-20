package com.itsluminous.cleartravel.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the app's three notification channels (trains / flights / reminders).
 * Idempotent — `createNotificationChannel` is a no-op for an existing id — so the app
 * shell calls [registerAll] once at startup and every module can post immediately.
 */
@Singleton
class NotificationChannelRegistrar
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun registerAll() {
            val manager = NotificationManagerCompat.from(context)
            manager.createNotificationChannel(
                channel(
                    id = NotificationChannels.CHANNEL_TRAINS,
                    name = context.getString(R.string.notifications_channel_trains_name),
                    description = context.getString(R.string.notifications_channel_trains_description),
                ),
            )
            manager.createNotificationChannel(
                channel(
                    id = NotificationChannels.CHANNEL_FLIGHTS,
                    name = context.getString(R.string.notifications_channel_flights_name),
                    description = context.getString(R.string.notifications_channel_flights_description),
                ),
            )
            manager.createNotificationChannel(
                channel(
                    id = NotificationChannels.CHANNEL_REMINDERS,
                    name = context.getString(R.string.notifications_channel_reminders_name),
                    description = context.getString(R.string.notifications_channel_reminders_description),
                ),
            )
        }

        private fun channel(
            id: String,
            name: String,
            description: String,
        ): NotificationChannel =
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).also {
                it.description = description
            }
    }
