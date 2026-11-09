package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.TrainTicket
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * ADR-049 (hermetic — in-memory Room): the Journeys tab opens on the FLIGHTS segment
 * for a user who has flights but no train ticket, with the flight card in view; the
 * moment any train ticket exists (an archived one included) the tab opens on Trains
 * as it always did. The default is decided once per process: a train added while the
 * user is on Flights does not move them.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class JourneysDefaultSegmentE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var flightRepository: FlightRepository

    @Inject
    lateinit var trainRepository: TrainRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun flightsOnly_journeysOpensOnFlights_andStaysThereWhenATrainArrives() {
        runBlocking { flightRepository.save(FlightJourney(airlineIata = AIRLINE, flightNumber = FLIGHT_NUMBER)) }

        composeRule.openJourneys()

        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).assertIsSelected()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_trains)).assertIsNotSelected()
        composeRule.waitForText("$AIRLINE $FLIGHT_NUMBER")

        // The first train of the session arrives (an import, a shared link…): the
        // decision was one-shot, so the user is not yanked off the Flights list.
        runBlocking { trainRepository.save(TrainTicket(pnr = PNR, trainNumber = TRAIN_NUMBER)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_flights)).assertIsSelected()
        composeRule.onNodeWithText("$AIRLINE $FLIGHT_NUMBER").assertExists()
    }

    @Test
    fun aTrainAndAFlight_journeysOpensOnTrains() {
        runBlocking {
            flightRepository.save(FlightJourney(airlineIata = AIRLINE, flightNumber = FLIGHT_NUMBER))
            trainRepository.save(TrainTicket(pnr = PNR, trainNumber = TRAIN_NUMBER))
        }

        composeRule.openJourneys()

        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_trains)).assertIsSelected()
        composeRule.waitForText(TRAIN_NUMBER, substring = true)
    }

    @Test
    fun anArchivedTrainAndAFlight_journeysOpensOnTrains() {
        runBlocking {
            flightRepository.save(FlightJourney(airlineIata = AIRLINE, flightNumber = FLIGHT_NUMBER))
            trainRepository.save(TrainTicket(pnr = PNR, trainNumber = TRAIN_NUMBER, archived = true))
        }

        composeRule.openJourneys()

        composeRule.onNodeWithText(composeRule.string(R.string.journeys_segment_trains)).assertIsSelected()
    }

    private companion object {
        const val AIRLINE = "6E"
        const val FLIGHT_NUMBER = "2049"
        const val PNR = "4455667788"
        const val TRAIN_NUMBER = "12951"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
