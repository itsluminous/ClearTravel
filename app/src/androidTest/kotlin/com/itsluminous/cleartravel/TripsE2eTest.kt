package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.feature.itinerary.R as ItineraryR

/**
 * Trips happy path (hermetic — in-memory Room, map never required): create a trip
 * from the FAB dialog and see it appear in the list; the dialog keeps typed input
 * through a tap outside it (ADR-041).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TripsE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun createTrip_appearsInList() {
        // Trips is the start destination — already on it after launch.
        composeRule
            .onNodeWithContentDescription(composeRule.string(ItineraryR.string.itinerary_add_trip))
            .performClick()
        composeRule
            .onNodeWithText(composeRule.string(ItineraryR.string.itinerary_trip_name_label))
            .performTextInput(TRIP_NAME)
        composeRule.onNodeWithText(composeRule.string(ItineraryR.string.itinerary_save)).performClick()

        composeRule.waitForText(TRIP_NAME)
    }

    /**
     * ADR-041: a data-entry dialog never dismisses on a tap outside — with gesture
     * navigation the touch DOWN of an edge swipe lands outside the dialog and used to
     * close it (losing the typed name) before the swipe even became "back". Back still
     * dismisses. The tap is injected at screen coordinates because the dialog is its
     * own window, which the platform closes on the raw outside DOWN.
     */
    @Test
    fun newTripDialog_keepsTypedInputOnOutsideTap_backDismisses() {
        composeRule
            .onNodeWithContentDescription(composeRule.string(ItineraryR.string.itinerary_add_trip))
            .performClick()
        val title = composeRule.string(ItineraryR.string.itinerary_new_trip_title)
        composeRule.waitForText(title)
        composeRule
            .onNodeWithText(composeRule.string(ItineraryR.string.itinerary_trip_name_label))
            .performTextInput(TRIP_NAME)
        Espresso.closeSoftKeyboard()

        // The dialog is horizontally inset by its Material margins; x=8px at mid-height
        // is outside it on every phone-sized display.
        val metrics = composeRule.activity.resources.displayMetrics
        E2e.tapScreen(x = 8f, y = metrics.heightPixels / 2f)
        composeRule.waitForIdle()

        composeRule.onNodeWithText(title).assertExists()
        composeRule.onNodeWithText(TRIP_NAME).assertExists()

        Espresso.pressBack()

        composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onAllNodesWithText(TRIP_NAME).assertCountEquals(0)
    }

    private companion object {
        const val TRIP_NAME = "Goa 2026"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
