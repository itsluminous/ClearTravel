package com.itsluminous.cleartravel.core.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** ADR-043: the "unlock to sync" nudge posts once per process and stays quiet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32]) // below the runtime permission so posting is allowed
class AppLockNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setUp() {
        NotificationChannelRegistrar(context).registerAll()
    }

    @Test
    fun `posts once per process, re-arms on clear, and is low priority + alert-once`() {
        val notifier = AppLockNotifier(context)
        assertThat(notifier.postedThisProcess).isFalse()

        assertThat(notifier.notifyUnlockToSync()).isTrue()
        assertThat(notifier.notifyUnlockToSync()).isFalse() // second run in the same process: no re-post
        assertThat(notifier.notifyUnlockToSync()).isFalse()
        assertThat(notifier.postedThisProcess).isTrue()
        assertThat(shadowOf(manager).allNotifications).hasSize(1)

        val notification = shadowOf(manager).getNotification(AppLockNotifier.NOTIFICATION_ID)
        assertThat(notification.priority).isEqualTo(NotificationCompat.PRIORITY_LOW)
        assertThat(notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(notification.flags and android.app.Notification.FLAG_AUTO_CANCEL).isNotEqualTo(0)
        assertThat(notification.channelId).isEqualTo(NotificationChannels.CHANNEL_REMINDERS)

        notifier.clear()
        assertThat(notifier.postedThisProcess).isFalse()
        assertThat(shadowOf(manager).allNotifications).isEmpty()
        assertThat(notifier.notifyUnlockToSync()).isTrue() // a later lock in the same process may nudge again
    }
}
