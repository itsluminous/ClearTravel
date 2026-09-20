package com.itsluminous.cleartravel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * from the FAB dialog and see it appear in the list.
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

    private companion object {
        const val TRIP_NAME = "Goa 2026"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
