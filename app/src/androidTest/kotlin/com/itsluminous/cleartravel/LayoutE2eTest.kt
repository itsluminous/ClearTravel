package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.ui.JOURNEYS_SEGMENT_TEST_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.feature.flights.R as FlightsR
import com.itsluminous.cleartravel.feature.itinerary.R as ItineraryR
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * Layout steering (hermetic, nothing seeded): the Journeys Trains|Flights segmented
 * control is docked at the BOTTOM of the tab (right above the app's NavigationBar)
 * and still switches segments; the Active|Archived quick filter on the trains,
 * flights and trips lists is one full-width, two-equal-segment control that did not
 * grow taller than a FilterChip.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LayoutE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun journeysSegment_isDockedAtBottom_andSwitchesSegments() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_filter_active))

        composeRule.assertDockedAboveNavBar(JOURNEYS_SEGMENT_TEST_TAG)
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_trains)).assertIsSelected()

        // Switching still works from the bottom: the Flights list (its FAB) appears.
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).assertIsSelected()
        composeRule.onNodeWithContentDescription(composeRule.string(FlightsR.string.flights_add)).assertExists()
        composeRule.assertDockedAboveNavBar(JOURNEYS_SEGMENT_TEST_TAG)

        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_trains)).performClick()
        composeRule.onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_add_ticket)).assertExists()
    }

    @Test
    fun activeArchivedFilter_spansFullWidth_onTrainsFlightsAndTrips() {
        // Trips tab is the start destination.
        composeRule.waitForText(composeRule.string(ItineraryR.string.itinerary_filter_active))
        composeRule.assertFullWidthFilterRow(
            activeText = composeRule.string(ItineraryR.string.itinerary_filter_active),
            archivedText = composeRule.string(ItineraryR.string.itinerary_filter_archived),
        )

        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_filter_active))
        composeRule.assertFullWidthFilterRow(
            activeText = composeRule.string(TrainsR.string.trains_filter_active),
            archivedText = composeRule.string(TrainsR.string.trains_filter_archived),
        )

        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).performClick()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_filter_active))
        composeRule.assertFullWidthFilterRow(
            activeText = composeRule.string(FlightsR.string.flights_filter_active),
            archivedText = composeRule.string(FlightsR.string.flights_filter_archived),
        )
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
