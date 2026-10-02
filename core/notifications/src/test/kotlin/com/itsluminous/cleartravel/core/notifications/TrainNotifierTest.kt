package com.itsluminous.cleartravel.core.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** ADR-044: the train journey reminder — channel, id stability, titles, text variants, PNR-link intent. */
@RunWith(RobolectricTestRunner::class)
class TrainNotifierTest {
    private lateinit var context: Context
    private lateinit var notifier: TrainNotifier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(NotificationPermissions.PERMISSION)
        NotificationChannelRegistrar(context).registerAll()
        notifier = TrainNotifier(context)
    }

    private fun manager(): NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun content(
        daysUntil: Long = 1,
        station: String = "Mumbai Central",
        status: String? = "WL 12",
    ) = TrainReminderContent(
        pnr = "8524167890",
        trainLabel = "12951 Mumbai Rajdhani",
        boardingStation = station,
        departureDayText = "Sat 4 Oct",
        departureText = "Sat 4 Oct, 16:35",
        daysUntilDeparture = daysUntil,
        statusSummary = status,
        notificationId = 4242,
    )

    @Test
    fun `posts on the trains channel, default priority, one slot per ticket`() {
        notifier.notifyJourneyReminder(content())
        notifier.notifyJourneyReminder(content(status = "CNF"))

        val posted = shadowOf(manager()).allNotifications.single()
        assertThat(posted.channelId).isEqualTo(NotificationChannels.CHANNEL_TRAINS)
        assertThat(posted.priority).isEqualTo(NotificationCompat.PRIORITY_DEFAULT)
        assertThat(posted.flags and android.app.Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(shadowOf(manager()).getNotification(4242)).isNotNull()
    }

    @Test
    fun `title follows the calendar distance`() {
        assertThat(notifier.title(content(daysUntil = 0))).isEqualTo("Train today: 12951 Mumbai Rajdhani")
        assertThat(notifier.title(content(daysUntil = 1))).isEqualTo("Train tomorrow: 12951 Mumbai Rajdhani")
        assertThat(notifier.title(content(daysUntil = 2))).isEqualTo("Train on Sat 4 Oct: 12951 Mumbai Rajdhani")
    }

    @Test
    fun `text carries station and status only when known`() {
        assertThat(notifier.text(content()))
            .isEqualTo("Departs Mumbai Central Sat 4 Oct, 16:35. Tap to check PNR & seat status (WL 12).")
        assertThat(notifier.text(content(status = null)))
            .isEqualTo("Departs Mumbai Central Sat 4 Oct, 16:35. Tap to check PNR & seat status.")
        assertThat(notifier.text(content(station = "", status = null)))
            .isEqualTo("Departs Sat 4 Oct, 16:35. Tap to check PNR & seat status.")
        assertThat(notifier.text(content(station = " ", status = "CNF")))
            .isEqualTo("Departs Sat 4 Oct, 16:35. Tap to check PNR & seat status (CNF).")
    }

    @Test
    fun `tap opens the package-scoped PNR link`() {
        notifier.notifyJourneyReminder(content())

        val posted = shadowOf(manager()).getNotification(4242)
        val intent = shadowOf(posted.contentIntent).savedIntent
        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.dataString).isEqualTo("cleartravel://pnr/8524167890")
        assertThat(intent.`package`).isEqualTo(context.packageName)
    }
}
