package com.itsluminous.cleartravel.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts every flight-journey notification type (spec feature 2): check-in
 * window open, gate assigned/changed, delay, cancellation, baggage belt on landing,
 * plus the polling fallback "status may have changed — tap to check".
 *
 * Content intents follow [DeepLinkContract] (launch intent + extras). Posting is
 * gated on [NotificationPermissions.canPost] — without the permission the call is a
 * no-op, never a crash. Notification ids are derived from the flight UUID so a newer
 * update of the same kind replaces the older one instead of stacking.
 */
@Singleton
class FlightNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notifyCheckInOpen(
            flightId: String,
            flightLabel: String,
        ) = post(
            flightId = flightId,
            kind = KIND_CHECK_IN,
            channel = NotificationChannels.CHANNEL_REMINDERS,
            title = context.getString(R.string.notifications_flight_check_in_open_title, flightLabel),
            text = context.getString(R.string.notifications_flight_check_in_open_text),
        )

        fun notifyGateAssigned(
            flightId: String,
            flightLabel: String,
            gate: String,
        ) = post(
            flightId = flightId,
            kind = KIND_GATE,
            channel = NotificationChannels.CHANNEL_FLIGHTS,
            title = context.getString(R.string.notifications_flight_gate_assigned_title, flightLabel),
            text = context.getString(R.string.notifications_flight_gate_assigned_text, gate),
        )

        fun notifyGateChanged(
            flightId: String,
            flightLabel: String,
            newGate: String,
            oldGate: String,
        ) = post(
            flightId = flightId,
            kind = KIND_GATE,
            channel = NotificationChannels.CHANNEL_FLIGHTS,
            title = context.getString(R.string.notifications_flight_gate_changed_title, flightLabel),
            text = context.getString(R.string.notifications_flight_gate_changed_text, newGate, oldGate),
        )

        fun notifyDelayed(
            flightId: String,
            flightLabel: String,
            newDepartureText: String?,
        ) = post(
            flightId = flightId,
            kind = KIND_STATUS,
            channel = NotificationChannels.CHANNEL_FLIGHTS,
            title = context.getString(R.string.notifications_flight_delayed_title, flightLabel),
            text =
                if (newDepartureText.isNullOrBlank()) {
                    context.getString(R.string.notifications_flight_delayed_text_no_time)
                } else {
                    context.getString(R.string.notifications_flight_delayed_text, newDepartureText)
                },
        )

        fun notifyCancelled(
            flightId: String,
            flightLabel: String,
        ) = post(
            flightId = flightId,
            kind = KIND_STATUS,
            channel = NotificationChannels.CHANNEL_FLIGHTS,
            title = context.getString(R.string.notifications_flight_cancelled_title, flightLabel),
            text = context.getString(R.string.notifications_flight_cancelled_text),
        )

        fun notifyBeltAssigned(
            flightId: String,
            flightLabel: String,
            belt: String,
        ) = post(
            flightId = flightId,
            kind = KIND_BELT,
            channel = NotificationChannels.CHANNEL_FLIGHTS,
            title = context.getString(R.string.notifications_flight_belt_title, flightLabel),
            text = context.getString(R.string.notifications_flight_belt_text, belt),
        )

        /** Polling fallback (spec): no headless scrape ran — nudge the user to check. */
        fun notifyStatusMayHaveChanged(
            flightId: String,
            flightLabel: String,
        ) = post(
            flightId = flightId,
            kind = KIND_CHECK_HINT,
            channel = NotificationChannels.CHANNEL_FLIGHTS,
            title = context.getString(R.string.notifications_flight_status_check_title, flightLabel),
            text = context.getString(R.string.notifications_flight_status_check_text),
        )

        /** Visible for tests: the exact notification that would be posted. */
        internal fun build(
            flightId: String,
            channel: String,
            title: String,
            text: String,
        ): Notification =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.notifications_ic_stat_flight)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent(flightId))
                .build()

        private fun post(
            flightId: String,
            kind: Int,
            channel: String,
            title: String,
            text: String,
        ) {
            if (!NotificationPermissions.canPost(context)) return
            @Suppress("MissingPermission")
            NotificationManagerCompat
                .from(context)
                .notify(notificationId(flightId, kind), build(flightId, channel, title, text))
        }

        private fun contentIntent(flightId: String): PendingIntent? {
            val launch =
                DeepLinkContract.launchIntent(
                    context = context,
                    target = DeepLinkContract.TARGET_FLIGHT,
                    entityId = flightId,
                ) ?: return null
            return PendingIntent.getActivity(
                context,
                flightId.hashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Stable per-flight-per-kind id: newer updates replace, kinds never collide. */
        internal fun notificationId(
            flightId: String,
            kind: Int,
        ): Int = flightId.hashCode() * KIND_SPREAD + kind

        internal companion object {
            const val KIND_CHECK_IN = 0
            const val KIND_GATE = 1
            const val KIND_STATUS = 2
            const val KIND_BELT = 3
            const val KIND_CHECK_HINT = 4
            const val KIND_SPREAD = 7
        }
    }
