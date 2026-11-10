package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.form.ARRIVAL_DAY_ROW_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.flights.R as FlightsR

/**
 * ADR-050 amendment / ADR-051 (hermetic — in-memory Room): an overnight leg's arrival
 * day is visible. The card prints `Dep 23:05 · Arr 07:24 +1`, the detail sheet
 * `Scheduled: 07:24 · Sun, 22 Nov`; a same-day leg shows neither; and the form's
 * `Arrives` chips let a hand-typed overnight flight land on the next day.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FlightArrivalDateE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var flightRepository: FlightRepository

    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            // AS 22: SEA 23:05 Sat 21 Nov → JFK 07:24 Sun 22 Nov (the ADR-050 fixture leg).
            flightRepository.save(
                FlightJourney(
                    airlineIata = OVERNIGHT_AIRLINE,
                    flightNumber = OVERNIGHT_NUMBER,
                    date = LocalDate.of(2026, 11, 21),
                    depAirport = "SEA",
                    arrAirport = "JFK",
                    schedDep = ZonedDateTime.of(2026, 11, 21, 23, 5, 0, 0, zone).toInstant(),
                    schedArr = ZonedDateTime.of(2026, 11, 22, 7, 24, 0, 0, zone).toInstant(),
                ),
            )
            // AI 2853: BOM 15:35 → BLR 17:25 on 23 Nov — same day, no suffix, no date.
            flightRepository.save(
                FlightJourney(
                    airlineIata = SAME_DAY_AIRLINE,
                    flightNumber = SAME_DAY_NUMBER,
                    date = LocalDate.of(2026, 11, 23),
                    depAirport = "BOM",
                    arrAirport = "BLR",
                    schedDep = ZonedDateTime.of(2026, 11, 23, 15, 35, 0, 0, zone).toInstant(),
                    schedArr = ZonedDateTime.of(2026, 11, 23, 17, 25, 0, 0, zone).toInstant(),
                ),
            )
        }
    }

    @Test
    fun overnightArrival_cardShowsPlusOne_detailShowsTheDay_sameDayShowsNeither() {
        composeRule.openJourneys()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).performClick()
        composeRule.waitForText("$OVERNIGHT_AIRLINE $OVERNIGHT_NUMBER")

        // Card lines.
        val plusOne = composeRule.string(FlightsR.string.flights_arrival_day_offset, 1)
        val overnightLine = composeRule.string(FlightsR.string.flights_card_dep_arr, "23:05", "07:24 $plusOne")
        val sameDayLine = composeRule.string(FlightsR.string.flights_card_dep_arr, "15:35", "17:25")
        composeRule.waitForText(overnightLine)
        composeRule.onNodeWithText(sameDayLine).assertExists()
        composeRule.onAllNodesWithText("17:25 $plusOne", substring = true).assertCountEquals(0)

        // Detail sheet of the overnight leg: the arrival carries its day, the departure does not.
        composeRule.onNodeWithText("$OVERNIGHT_AIRLINE $OVERNIGHT_NUMBER").performClick()
        val scheduled = composeRule.string(FlightsR.string.flights_label_scheduled)
        val arrivalDay = DAY_FORMAT.format(ZonedDateTime.of(2026, 11, 22, 7, 24, 0, 0, zone))
        val arrivalWithDay = composeRule.string(FlightsR.string.flights_time_with_day, "07:24", arrivalDay)
        composeRule.waitForText("$scheduled: $arrivalWithDay")
        composeRule.onNodeWithText("$scheduled: 23:05").assertExists()
        Espresso.pressBack()

        // Detail sheet of the same-day leg: time only.
        composeRule.waitForText(sameDayLine)
        composeRule.onNodeWithText("$SAME_DAY_AIRLINE $SAME_DAY_NUMBER").performClick()
        composeRule.waitForText("$scheduled: 17:25")
        composeRule.onAllNodesWithText("$scheduled: 17:25 ", substring = true).assertCountEquals(0)
    }

    /** ADR-051: typing 23:05 → 07:24 and picking "Next day" stores the arrival on the following day. */
    @Test
    fun arrivesNextDayChip_datesTheTypedArrivalOnTheNextDay() {
        composeRule.openJourneys()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).performClick()
        composeRule
            .onNodeWithContentDescription(composeRule.string(FlightsR.string.flights_add))
            .performClick()
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_add_manual)).performClick()

        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_airline))
            .performTextInput(TYPED_AIRLINE)
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_flight_number))
            .performTextInput(TYPED_NUMBER)
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_date))
            .performClick()
        composeRule.pickTodayInDatePicker()
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_dep_time))
            .performScrollTo()
            .performTextInput("23:05")
        // The chips appear only once an arrival time is typed.
        composeRule.onAllNodes(hasTestTag(ARRIVAL_DAY_ROW_TAG)).assertCountEquals(0)
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_field_arr_time))
            .performScrollTo()
            .performTextInput("07:24")
        composeRule.onNode(hasTestTag(ARRIVAL_DAY_ROW_TAG)).performScrollTo().assertExists()
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_form_arrival_next_day))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_form_save))
            .performScrollTo()
            .performClick()

        val plusOne = composeRule.string(FlightsR.string.flights_arrival_day_offset, 1)
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_card_dep_arr, "23:05", "07:24 $plusOne"))
        val saved =
            runBlocking {
                flightRepository.observeActive().first().single {
                    it.airlineIata == TYPED_AIRLINE && it.flightNumber == TYPED_NUMBER
                }
            }
        val depDay = saved.schedDep!!.atZone(zone).toLocalDate()
        assertThat(saved.schedArr!!.atZone(zone).toLocalDate()).isEqualTo(depDay.plusDays(1))
    }

    private companion object {
        const val OVERNIGHT_AIRLINE = "AS"
        const val OVERNIGHT_NUMBER = "22"
        const val SAME_DAY_AIRLINE = "AI"
        const val SAME_DAY_NUMBER = "2853"
        const val TYPED_AIRLINE = "QR"
        const val TYPED_NUMBER = "573"

        /** The detail sheet's compact day (`Sun, 22 Nov`) — `FlightUiFormat.DAY_FORMAT`. */
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
