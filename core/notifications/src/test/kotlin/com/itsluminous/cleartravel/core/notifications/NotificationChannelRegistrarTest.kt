package com.itsluminous.cleartravel.core.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationChannelRegistrarTest {
    private lateinit var context: Context
    private lateinit var registrar: NotificationChannelRegistrar

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registrar = NotificationChannelRegistrar(context)
    }

    private fun manager(): NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `registerAll creates the trains, flights and reminders channels`() {
        registrar.registerAll()

        val ids = manager().notificationChannels.map { it.id }
        assertThat(ids).containsAtLeast(
            NotificationChannels.CHANNEL_TRAINS,
            NotificationChannels.CHANNEL_FLIGHTS,
            NotificationChannels.CHANNEL_REMINDERS,
        )
    }

    @Test
    fun `channels carry localized names and descriptions`() {
        registrar.registerAll()

        val flights = manager().getNotificationChannel(NotificationChannels.CHANNEL_FLIGHTS)
        assertThat(flights.name.toString()).isEqualTo(context.getString(R.string.notifications_channel_flights_name))
        assertThat(flights.description).isEqualTo(context.getString(R.string.notifications_channel_flights_description))
    }

    @Test
    fun `registerAll is idempotent`() {
        registrar.registerAll()
        registrar.registerAll()

        val appChannels =
            manager().notificationChannels.filter {
                it.id in
                    setOf(
                        NotificationChannels.CHANNEL_TRAINS,
                        NotificationChannels.CHANNEL_FLIGHTS,
                        NotificationChannels.CHANNEL_REMINDERS,
                    )
            }
        assertThat(appChannels).hasSize(3)
    }
}
