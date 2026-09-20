package com.itsluminous.cleartravel.core.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FlightNotifierTest {
    private lateinit var context: Context
    private lateinit var notifier: FlightNotifier

    private val flightId = "11111111-2222-3333-4444-555555555555"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(NotificationPermissions.PERMISSION)
        registerLauncherActivity()
        NotificationChannelRegistrar(context).registerAll()
        notifier = FlightNotifier(context)
    }

    private fun registerLauncherActivity() {
        val component = ComponentName(context.packageName, "com.itsluminous.cleartravel.MainActivity")
        val shadowPm = shadowOf(context.packageManager)
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun manager(): NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `gate change posts on the flights channel`() {
        notifier.notifyGateChanged(flightId, "AI 101", newGate = "24", oldGate = "12")

        val posted = shadowOf(manager()).allNotifications.single()
        assertThat(posted.channelId).isEqualTo(NotificationChannels.CHANNEL_FLIGHTS)
    }

    @Test
    fun `check-in open posts on the reminders channel`() {
        notifier.notifyCheckInOpen(flightId, "AI 101")

        val posted = shadowOf(manager()).allNotifications.single()
        assertThat(posted.channelId).isEqualTo(NotificationChannels.CHANNEL_REMINDERS)
    }

    @Test
    fun `content intent carries the deep-link contract extras`() {
        notifier.notifyCancelled(flightId, "AI 101")

        val posted = shadowOf(manager()).allNotifications.single()
        val savedIntent = shadowOf(posted.contentIntent).savedIntent
        assertThat(savedIntent.getStringExtra(DeepLinkContract.EXTRA_TARGET))
            .isEqualTo(DeepLinkContract.TARGET_FLIGHT)
        assertThat(savedIntent.getStringExtra(DeepLinkContract.EXTRA_ENTITY_ID)).isEqualTo(flightId)
    }

    @Test
    fun `same kind replaces, different kinds stack`() {
        notifier.notifyGateAssigned(flightId, "AI 101", gate = "12")
        notifier.notifyGateChanged(flightId, "AI 101", newGate = "24", oldGate = "12")
        notifier.notifyBeltAssigned(flightId, "AI 101", belt = "7")

        // Gate assigned + gate changed share an id (KIND_GATE); belt is distinct.
        assertThat(shadowOf(manager()).allNotifications).hasSize(2)
    }

    @Test
    fun `notification ids are stable and kind-distinct`() {
        val gateId = notifier.notificationId(flightId, FlightNotifier.KIND_GATE)
        assertThat(notifier.notificationId(flightId, FlightNotifier.KIND_GATE)).isEqualTo(gateId)
        assertThat(notifier.notificationId(flightId, FlightNotifier.KIND_BELT)).isNotEqualTo(gateId)
    }

    @Test
    fun `delay without a time falls back to generic text`() {
        notifier.notifyDelayed(flightId, "AI 101", newDepartureText = null)

        val posted = shadowOf(manager()).allNotifications.single()
        assertThat(posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            .isEqualTo(context.getString(R.string.notifications_flight_delayed_text_no_time))
    }
}
