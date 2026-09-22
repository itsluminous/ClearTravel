package com.itsluminous.cleartravel

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.model.Trip
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.itinerary.R as ItineraryR
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * Cross-tab integration, ADR-028 (hermetic — in-memory Room): a trip whose itinerary
 * has a commute leg linked to a seeded train ticket. Journeys → ticket detail sheet
 * shows a "Part of: <trip> · Day 2" row; tapping it lands on the Trips tab with that
 * trip's detail open. From the leg's sheet, "Open in Journeys" lands back on the
 * ticket's detail sheet. (The full add-a-journey-from-the-form hand-off exercises the
 * system file picker / live form and is device-verified instead.)
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class CrossTabE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var tripRepository: TripRepository

    @Inject
    lateinit var itineraryRepository: ItineraryRepository

    @Inject
    lateinit var trainRepository: TrainRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            val trip = tripRepository.save(Trip(name = TRIP_NAME))
            val ticket = trainRepository.save(TrainTicket(pnr = PNR, trainNumber = TRAIN_NUMBER))
            itineraryRepository.save(
                ItineraryItem(
                    tripId = trip.id,
                    dayIndex = 1,
                    type = ItineraryItemType.COMMUTE,
                    name = "$FROM - $TO",
                    commuteMode = CommuteMode.TRAIN,
                    fromName = FROM,
                    toName = TO,
                    linkedJourneyId = ticket.id,
                    linkedJourneyType = JourneyType.TRAIN,
                ),
            )
        }
    }

    @Test
    fun linkedTicketSheet_showsPartOfRow_andOpensTheTrip_thenLegSheetOpensTheTicket() {
        // Journeys → Trains (default segment) → the seeded card → detail sheet.
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.waitForText(TRAIN_NUMBER)
        composeRule.onNodeWithText(TRAIN_NUMBER).performClick()
        composeRule.waitForText(PNR)

        // "Part of" row: trip name + 1-based day.
        val partOf = composeRule.string(TrainsR.string.trains_detail_part_of_trip, TRIP_NAME, 2)
        composeRule.waitForText(partOf)
        composeRule.onNodeWithText(partOf).performScrollTo().performClick()

        // Landed on the Trips tab with the trip's detail (timeline toggle + the leg card).
        composeRule.waitForText(composeRule.string(ItineraryR.string.itinerary_view_timeline))
        val route = composeRule.string(ItineraryR.string.itinerary_commute_route, FROM, TO)
        composeRule.waitForText(route)

        // Leg sheet → "Open in Journeys" → back on the ticket's detail sheet.
        composeRule.onNodeWithText(route).performClick()
        composeRule.waitForText(composeRule.string(ItineraryR.string.itinerary_open_in_journeys))
        composeRule.onNodeWithText(composeRule.string(ItineraryR.string.itinerary_open_in_journeys)).performClick()
        composeRule.waitForText(PNR)
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_detail_check_status)).assertExists()
    }

    private companion object {
        const val TRIP_NAME = "Kerala 2026"
        const val PNR = "4409876543"
        const val TRAIN_NUMBER = "12626"
        const val FROM = "MAS"
        const val TO = "TVC"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
