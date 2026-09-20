package com.itsluminous.cleartravel.core.notifications

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationGatesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `launchIntent is null when the package has no launcher activity`() {
        val intent = DeepLinkContract.launchIntent(context, DeepLinkContract.TARGET_FLIGHT, "some-id")

        assertThat(intent).isNull()
    }

    @Test
    @Config(sdk = [33])
    fun `canPost is false on Android 13 without the permission`() {
        shadowOf(context as Application).denyPermissions(NotificationPermissions.PERMISSION)

        assertThat(NotificationPermissions.canPost(context)).isFalse()
        assertThat(NotificationPermissions.needsRequest(context)).isTrue()
    }

    @Test
    @Config(sdk = [33])
    fun `canPost is true on Android 13 with the permission granted`() {
        shadowOf(context as Application).grantPermissions(NotificationPermissions.PERMISSION)

        assertThat(NotificationPermissions.canPost(context)).isTrue()
    }

    @Test
    @Config(sdk = [32])
    fun `canPost is always true below Android 13`() {
        assertThat(NotificationPermissions.canPost(context)).isTrue()
        assertThat(NotificationPermissions.needsRequest(context)).isFalse()
    }

    @Test
    @Config(sdk = [33])
    fun `posting without permission is a silent no-op`() {
        shadowOf(context as Application).denyPermissions(NotificationPermissions.PERMISSION)
        NotificationChannelRegistrar(context).registerAll()

        FlightNotifier(context).notifyCancelled("id-1", "AI 101")

        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }
}
