package com.itsluminous.cleartravel.feature.flights.detail

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeItineraryRepository
import com.itsluminous.cleartravel.feature.flights.FakeTripRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** ADR-028 "Part of" rows for a flight: reverse lookup joined with live trips. */
class FlightTripLinksViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val itineraryRepository = FakeItineraryRepository()
    private val tripRepository = FakeTripRepository()
    private val viewModel = FlightTripLinksViewModel(itineraryRepository, tripRepository)

    @Test
    fun `lists every live leg with its trip name and day in itinerary order`() =
        runTest {
            tripRepository.trips.value =
                listOf(Fixtures.trip(id = "goa", name = "Goa"), Fixtures.trip(id = "kerala", name = "Kerala"))
            itineraryRepository.items.value =
                listOf(
                    Fixtures.itineraryItem(
                        tripId = "kerala",
                        dayIndex = 3,
                        type = ItineraryItemType.COMMUTE,
                        linkedJourneyId = "f1",
                        linkedJourneyType = JourneyType.FLIGHT,
                    ),
                    Fixtures.itineraryItem(
                        tripId = "goa",
                        dayIndex = 1,
                        type = ItineraryItemType.COMMUTE,
                        linkedJourneyId = "f1",
                        linkedJourneyType = JourneyType.FLIGHT,
                    ),
                    Fixtures.itineraryItem(tripId = "goa", linkedJourneyId = "f2", linkedJourneyType = JourneyType.FLIGHT),
                )

            viewModel.observeLinkedTrips("f1").test {
                assertThat(awaitItem())
                    .containsExactly(LinkedTrip("goa", "Goa", 1), LinkedTrip("kerala", "Kerala", 3))
                    .inOrder()
            }
        }

    @Test
    fun `is empty without legs and reacts when a trip appears`() =
        runTest {
            itineraryRepository.items.value =
                listOf(
                    Fixtures.itineraryItem(
                        tripId = "t",
                        type = ItineraryItemType.COMMUTE,
                        linkedJourneyId = "f1",
                        linkedJourneyType = JourneyType.FLIGHT,
                    ),
                )

            viewModel.observeLinkedTrips("f1").test {
                assertThat(awaitItem()).isEmpty()

                tripRepository.trips.value = listOf(Fixtures.trip(id = "t", name = "Trip"))

                assertThat(awaitItem()).containsExactly(LinkedTrip("t", "Trip", 0))
            }
            viewModel.observeLinkedTrips("nobody").test { assertThat(awaitItem()).isEmpty() }
        }
}
