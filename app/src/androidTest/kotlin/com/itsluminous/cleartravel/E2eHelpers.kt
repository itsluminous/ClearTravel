package com.itsluminous.cleartravel

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.annotation.StringRes
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.notifications.NotificationPermissions
import com.itsluminous.cleartravel.core.designsystem.R as DesignR

/** Shared plumbing for the e2e suite. */
object E2e {
    const val WAIT_TIMEOUT_MILLIS = 15_000L

    /**
     * Injects a raw touch tap at SCREEN coordinates through UiAutomation — it reaches
     * whichever window is on top (a dialog's own window included), unlike Compose's
     * semantics-driven clicks, which only address nodes.
     */
    fun tapScreen(
        x: Float,
        y: Float,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true)
            event.recycle()
        }
    }

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

/**
 * Asserts the Active|Archived quick filter is ONE full-width control: two chips of
 * equal width on the same line that together span (almost) the whole root width, and
 * no taller than a Material 3 FilterChip's 48dp minimum interactive size — the row
 * must not have grown into a taller control.
 */
fun AndroidComposeTestRule<*, MainActivity>.assertFullWidthFilterRow(
    activeText: String,
    archivedText: String,
) {
    val root = onRoot().getBoundsInRoot()
    val active = onNodeWithText(activeText).getBoundsInRoot()
    val archived = onNodeWithText(archivedText).getBoundsInRoot()
    val rootWidth = root.right - root.left
    val activeWidth = active.right - active.left
    val archivedWidth = archived.right - archived.left
    assertThat((activeWidth - archivedWidth).value).isWithin(1f).of(0f)
    assertThat((active.top - archived.top).value).isWithin(1f).of(0f)
    // 16dp edge padding on each side + 8dp gap = 40dp of the row is not chip.
    assertThat((activeWidth + archivedWidth).value).isAtLeast((rootWidth - 41.dp).value)
    assertThat((active.bottom - active.top).value).isAtMost(48.5f)
}

/**
 * Asserts the node with [tag] is docked at the BOTTOM of the tab content: nothing but
 * the app's NavigationBar (found by its Journeys label) sits below it.
 */
fun AndroidComposeTestRule<*, MainActivity>.assertDockedAboveNavBar(tag: String) {
    val docked = onNodeWithTag(tag).getBoundsInRoot()
    val navLabel = onNodeWithText(string(R.string.nav_journeys)).getBoundsInRoot()
    val root = onRoot().getBoundsInRoot()
    // Below the vertical middle of the screen and above the nav bar's label.
    assertThat(docked.top.value).isGreaterThan(((root.bottom - root.top) / 2).value)
    assertThat(docked.bottom.value).isAtMost(navLabel.top.value)
}

/**
 * Picks TODAY in the shared `LocalDatePickerDialog` that a tap on a date field opened:
 * Material 3 labels the current day's cell "Today, <full date>", so it is the one day
 * every locale/month state exposes without navigation. Confirms with the shared OK.
 */
fun AndroidComposeTestRule<*, MainActivity>.pickTodayInDatePicker() {
    waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
        onAllNodes(hasText(M3_TODAY_PREFIX, substring = true) and hasClickAction())
            .fetchSemanticsNodes()
            .isNotEmpty()
    }
    onNode(hasText(M3_TODAY_PREFIX, substring = true) and hasClickAction()).performClick()
    onNodeWithText(string(DesignR.string.designsystem_date_picker_ok)).performClick()
}

/** Material 3's `m3c_date_picker_today_description` (English test locale). */
private const val M3_TODAY_PREFIX = "Today"
