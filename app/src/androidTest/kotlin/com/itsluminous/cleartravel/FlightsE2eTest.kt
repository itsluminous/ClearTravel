package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.feature.flights.R as FlightsR

/**
 * Flights happy path (hermetic — in-memory Room, no scrape/OCR touched): add a
 * flight manually, open its card, and see the route in the detail sheet; adding the
 * same airline + number + date again is refused (ADR-025).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FlightsE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun addFlightManually_detailSheetShowsRoute() {
        // Journeys tab → Flights segment.
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).performClick()

        // FAB → Enter manually.
        composeRule
            .onNodeWithContentDescription(composeRule.string(FlightsR.string.flights_add))
            .performClick()
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_add_manual)).performClick()

        // Fill the form (route fields included so the detail sheet has one to show).
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_airline))
            .performTextInput(AIRLINE)
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_flight_number))
            .performTextInput(FLIGHT_NUMBER)
        // The date is picker-only (read-only field): tap it, pick today, confirm.
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_date))
            .performClick()
        composeRule.pickTodayInDatePicker()
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_dep_airport))
            .performScrollTo()
            .performTextInput(DEP)
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_arr_airport))
            .performScrollTo()
            .performTextInput(ARR)
        // "Save" (exact match — never the "Save & check status" scrape path).
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_form_save))
            .performScrollTo()
            .performClick()

        // Back on the list: the saved card appears; open the detail sheet.
        val cardTitle = "$AIRLINE $FLIGHT_NUMBER"
        composeRule.waitForText(cardTitle)
        composeRule.onNodeWithText(cardTitle).performClick()

        // Detail sheet shows the route (card + sheet both render it → 2 nodes).
        val separator = composeRule.string(FlightsR.string.flights_card_route_separator)
        val route = "$DEP $separator $ARR"
        composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(route).fetchSemanticsNodes().size >= 2
        }
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_action_check_status))
            .assertExists()
    }

    /** ADR-025: a second journey with the same airline + number + date is refused — notice shown, one card stays. */
    @Test
    fun addSameFlightTwice_isRefusedWithNotice_andKeepsOneCard() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).performClick()

        // Second pass types the zero-padded number: "0777" must be recognised as "777".
        listOf(DUPLICATE_FLIGHT_NUMBER, "0$DUPLICATE_FLIGHT_NUMBER").forEach { number ->
            composeRule
                .onNodeWithContentDescription(composeRule.string(FlightsR.string.flights_add))
                .performClick()
            composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_add_manual)).performClick()
            composeRule
                .onNodeWithText(composeRule.string(FlightsR.string.flights_field_airline))
                .performTextInput(DUPLICATE_AIRLINE)
            composeRule
                .onNodeWithText(composeRule.string(FlightsR.string.flights_field_flight_number))
                .performTextInput(number)
            composeRule
                .onNodeWithText(composeRule.string(FlightsR.string.flights_field_date))
                .performClick()
            composeRule.pickTodayInDatePicker()
            composeRule
                .onNodeWithText(composeRule.string(FlightsR.string.flights_form_save))
                .performScrollTo()
                .performClick()
            // Back on the list (FAB visible again) — the first save wrote the card,
            // the second was refused and just returned to the list.
            composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
                composeRule
                    .onAllNodesWithContentDescription(composeRule.string(FlightsR.string.flights_add))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }

        // The refusal is explained, and exactly one card carries the flight: nothing
        // was written twice.
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_form_duplicate))
        composeRule.onAllNodesWithText("$DUPLICATE_AIRLINE $DUPLICATE_FLIGHT_NUMBER").assertCountEquals(1)
        composeRule.onAllNodesWithText("$DUPLICATE_AIRLINE 0$DUPLICATE_FLIGHT_NUMBER").assertCountEquals(0)
    }

    private companion object {
        const val AIRLINE = "6E"
        const val DUPLICATE_AIRLINE = "AI"
        const val DUPLICATE_FLIGHT_NUMBER = "777"
        const val FLIGHT_NUMBER = "2345"
        const val DEP = "BLR"
        const val ARR = "DEL"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
