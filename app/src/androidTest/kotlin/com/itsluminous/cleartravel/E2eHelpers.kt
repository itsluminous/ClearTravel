package com.itsluminous.cleartravel

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.itsluminous.cleartravel.core.notifications.NotificationPermissions

/** Shared plumbing for the e2e suite. */
object E2e {
    const val WAIT_TIMEOUT_MILLIS = 15_000L

    /**
     * Pre-grants POST_NOTIFICATIONS so the shell's one-time permission request never
     * shows the system dialog over the UI under test. Call from `@BeforeClass`.
     */
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                NotificationPermissions.PERMISSION,
            )
        }
    }
}

/** Resolves a string resource against the activity under test. */
fun AndroidComposeTestRule<*, MainActivity>.string(
    @StringRes resId: Int,
    vararg args: Any,
): String = activity.getString(resId, *args)

/** Waits until at least one node with exactly [text] exists (Room writes are async). */
fun AndroidComposeTestRule<*, MainActivity>.waitForText(
    text: String,
    substring: Boolean = false,
) {
    waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
        onAllNodesWithText(text, substring = substring)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
}
