package com.itsluminous.cleartravel.core.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a train journey reminder says (ADR-044). Built by the trains feature, which
 * owns the ticket model and the date formatting; this module only owns the strings
 * and the intent. Every text field is already localised/formatted.
 */
data class TrainReminderContent(
    /** 10-digit PNR — only used to build the content URI and the stable notification id. */
    val pnr: String,
    /** "12951 Mumbai Rajdhani" (number + name, whichever are known). */
    val trainLabel: String,
    /** Boarding station as stored on the ticket; blank when unknown. */
    val boardingStation: String,
    /** "Sat 4 Oct" — the departure day, for the "Train on …" title. */
    val departureDayText: String,
    /** "Sat 4 Oct, 16:35" — day + time (or day only when the route is unknown). */
    val departureText: String,
    /** Calendar days from today to the departure day in the device zone: 0 today, 1 tomorrow, … */
    val daysUntilDeparture: Long,
    /** "CNF" / "WL 12, RAC 3" — distinct known passenger statuses; null when nothing is known. */
    val statusSummary: String?,
    /** Stable per-ticket id (`PnrHash.notificationId`), so a re-post replaces instead of stacking. */
    val notificationId: Int,
)

/**
 * Posts the train journey reminder (ADR-044): "Train tomorrow: 12951 Mumbai Rajdhani —
 * Departs Mumbai Central Sat 4 Oct, 16:35. Tap to check PNR & seat status (WL 12)".
 * On the trains channel, default priority. Tapping opens the ADR-020 PNR link
 * `cleartravel://pnr/<pnr>` scoped to this package, which the shell routes to the
 * ticket's PNR status check (the captcha WebView) once unlocked — one tap = refresh.
 */
@Singleton
class TrainNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Posts (or replaces) the reminder for [content]'s ticket; a no-op without the permission. */
        fun notifyJourneyReminder(content: TrainReminderContent) {
            if (!NotificationPermissions.canPost(context)) return
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(content.notificationId, build(content))
        }

        /** Visible for tests: the exact notification that would be posted. */
        internal fun build(content: TrainReminderContent): Notification {
            val text = text(content)
            return NotificationCompat
                .Builder(context, NotificationChannels.CHANNEL_TRAINS)
                .setSmallIcon(R.drawable.notifications_ic_stat_train)
                .setContentTitle(title(content))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(content))
                .build()
        }

        internal fun title(content: TrainReminderContent): String =
            when (content.daysUntilDeparture) {
                0L -> context.getString(R.string.notifications_train_reminder_title_today, content.trainLabel)
                1L -> context.getString(R.string.notifications_train_reminder_title_tomorrow, content.trainLabel)
                else -> context.getString(R.string.notifications_train_reminder_title_on_day, content.trainLabel, content.departureDayText)
            }

        internal fun text(content: TrainReminderContent): String {
            val station = content.boardingStation.trim()
            val status = content.statusSummary?.trim()?.takeIf(String::isNotEmpty)
            val resId =
                when {
                    station.isEmpty() && status == null -> R.string.notifications_train_reminder_text_no_station
                    station.isEmpty() -> R.string.notifications_train_reminder_text_no_station_with_status
                    status == null -> R.string.notifications_train_reminder_text
                    else -> R.string.notifications_train_reminder_text_with_status
                }
            return context.getString(resId, station, content.departureText, status.orEmpty())
        }

        private fun contentIntent(content: TrainReminderContent): PendingIntent {
            val view =
                Intent(Intent.ACTION_VIEW, pnrLink(content.pnr))
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                content.notificationId,
                view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        companion object {
            /** The ADR-020 custom-scheme PNR link the app's manifest already handles. */
            fun pnrLink(pnr: String): Uri = Uri.parse("cleartravel://pnr/${pnr.trim()}")
        }
    }
