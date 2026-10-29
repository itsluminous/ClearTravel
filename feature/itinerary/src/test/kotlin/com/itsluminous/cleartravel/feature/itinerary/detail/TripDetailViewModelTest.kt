package com.itsluminous.cleartravel.feature.itinerary.detail

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.itinerary.ItineraryMessage
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeFlightRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeItineraryRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeNetworkAvailability
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakePlaceLocator
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTrainRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTripRepository
import com.itsluminous.cleartravel.feature.itinerary.place.PlaceBackfillRunner
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Clock

private const val TRIP_ID = "trip-1"

class TripDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tripRepository = FakeTripRepository()
    private val itineraryRepository = FakeItineraryRepository()
    private val trainRepository = FakeTrainRepository()
    private val flightRepository = FakeFlightRepository()
    private val locator = FakePlaceLocator()
    private val network = FakeNetworkAvailability()
    private val backfillRunner = PlaceBackfillRunner(itineraryRepository, locator, network, Clock.systemUTC(), log = {})

    private fun viewModel(): TripDetailViewModel =
        TripDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("tripId" to TRIP_ID)),
            tripRepository = tripRepository,
            itineraryRepository = itineraryRepository,
            trainRepository = trainRepository,
            flightRepository = flightRepository,
            backfillRunner = backfillRunner,
        )

    @Test
    fun `journeyLabels resolves linked trains and flights by id and skips unknown journeys`() =
        runTest {
            trainRepository.tickets.value = listOf(Fixtures.trainTicket(id = "t-1", trainNumber = "12951"))
            flightRepository.flights.value = listOf(Fixtures.flightJourney(id = "f-1", airlineIata = "6E", flightNumber = "2001"))
            itineraryRepository.items.value =
                listOf(
                    Fixtures.itineraryItem(tripId = TRIP_ID, linkedJourneyId = "t-1", linkedJourneyType = JourneyType.TRAIN),
                    Fixtures.itineraryItem(tripId = TRIP_ID, linkedJourneyId = "f-1", linkedJourneyType = JourneyType.FLIGHT),
                    Fixtures.itineraryItem(tripId = TRIP_ID, linkedJourneyId = "gone", linkedJourneyType = JourneyType.FLIGHT),
                    Fixtures.itineraryItem(tripId = TRIP_ID),
                )

            viewModel().journeyLabels.test {
                val labels = awaitItem().ifEmpty { awaitItem() }
                assertThat(labels).containsExactly("t-1", "12951", "f-1", "6E 2001")
            }
        }

    @Test
    fun `journeyLabels is empty when no leg links a journey`() =
        runTest {
            itineraryRepository.items.value = listOf(Fixtures.itineraryItem(tripId = TRIP_ID))

            viewModel().journeyLabels.test {
                assertThat(awaitItem()).isEmpty()
                expectNoEvents()
            }
        }

    @Test
    fun `days groups only this trip's items in day and order sequence`() =
        runTest {
            tripRepository.trips.value = listOf(Fixtures.trip(id = TRIP_ID))
            itineraryRepository.items.value =
                listOf(
                    Fixtures.itineraryItem(tripId = TRIP_ID, dayIndex = 1, orderInDay = 0, name = "D2"),
                    Fixtures.itineraryItem(tripId = TRIP_ID, dayIndex = 0, orderInDay = 1, name = "D1 second"),
                    Fixtures.itineraryItem(tripId = TRIP_ID, dayIndex = 0, orderInDay = 0, name = "D1 first"),
                    Fixtures.itineraryItem(tripId = "other-trip", dayIndex = 0, orderInDay = 0, name = "Other"),
                )

            viewModel().days.test {
                val days = awaitItem().ifEmpty { awaitItem() }
                assertThat(days.map { it.dayIndex }).containsExactly(0, 1).inOrder()
                assertThat(days.flatMap { it.items }.map { it.name })
                    .containsExactly("D1 first", "D1 second", "D2")
                    .inOrder()
            }
        }

    @Test
    fun `mapContent derives markers from located items only`() =
        runTest {
            itineraryRepository.items.value =
                listOf(
                    Fixtures.itineraryItem(tripId = TRIP_ID, latitude = 12.0, longitude = 77.0),
                    Fixtures.itineraryItem(tripId = TRIP_ID, latitude = null, longitude = null),
                )

            viewModel().mapContent.test {
                val content =
                    awaitItem().let { if (it.markers.isEmpty()) awaitItem() else it }
                assertThat(content.markers).hasSize(1)
            }
        }

    @Test
    fun `reorderDay persists the dropped ordering`() =
        runTest {
            val first = Fixtures.itineraryItem(id = "a", tripId = TRIP_ID, dayIndex = 0, orderInDay = 0, name = "A")
            val second = Fixtures.itineraryItem(id = "b", tripId = TRIP_ID, dayIndex = 0, orderInDay = 1, name = "B")
            itineraryRepository.items.value = listOf(first, second)
            val viewModel = viewModel()

            viewModel.reorderDay(dayIndex = 0, from = 1, to = 0)

            val byId = itineraryRepository.items.value.associateBy { it.id }
            assertThat(byId.getValue("b").orderInDay).isEqualTo(0)
            assertThat(byId.getValue("a").orderInDay).isEqualTo(1)
        }

    @Test
    fun `reorderDay with a same-position drop persists nothing`() =
        runTest {
            val first = Fixtures.itineraryItem(id = "a", tripId = TRIP_ID, dayIndex = 0, orderInDay = 0)
            itineraryRepository.items.value = listOf(first)
            val viewModel = viewModel()

            viewModel.reorderDay(dayIndex = 0, from = 0, to = 0)

            assertThat(
                itineraryRepository.items.value
                    .single()
                    .orderInDay,
            ).isEqualTo(0)
        }

    @Test
    fun `deleteItem removes the item and reports it`() =
        runTest {
            val item = Fixtures.itineraryItem(tripId = TRIP_ID)
            itineraryRepository.items.value = listOf(item)
            val viewModel = viewModel()

            viewModel.deleteItem(item.id)

            assertThat(itineraryRepository.items.value).isEmpty()
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.ITEM_DELETED)
        }

    /** ADR-048: opening the screen backfills this trip's unplaced places; the map's states follow Room. */
    @Test
    fun `init backfills unplaced places and exposes locating progress and the leftovers`() =
        runTest {
            val rumi = Fixtures.itineraryItem(tripId = TRIP_ID, name = RUMI, latitude = null, longitude = null)
            val lost = Fixtures.itineraryItem(tripId = TRIP_ID, name = "Nowhere, Atlantis", latitude = null, longitude = null)
            val lunch = Fixtures.itineraryItem(tripId = TRIP_ID, name = "Lunch", latitude = null, longitude = null)
            itineraryRepository.items.value = listOf(rumi, lost, lunch)
            locator.answer(RUMI, 26.8712, 80.9122)

            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()

            viewModel.unplaced.test {
                assertThat(awaitItem().map { it.name }).containsExactly("Nowhere, Atlantis")
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(viewModel.locating.value).isNull()
            assertThat(
                itineraryRepository.items.value
                    .first { it.id == rumi.id }
                    .latitude,
            ).isEqualTo(26.8712)
            viewModel.mapContent.test {
                // WhileSubscribed: the empty seed may or may not precede the Room-derived content.
                var content = awaitItem()
                if (content.isEmpty) content = awaitItem()
                assertThat(content.markers.map { it.item.id }).containsExactly(rumi.id)
                cancelAndIgnoreRemainingEvents()
            }
            assertThat(locator.calls.map { it.second }).containsExactly(RUMI, "Nowhere, Atlantis")
        }

    /** A place saved without a point while the screen is open (item form, Maps intake) is picked up too. */
    @Test
    fun `a place added while the screen is open is backfilled without re-trying the ones already tried`() =
        runTest {
            val lost = Fixtures.itineraryItem(tripId = TRIP_ID, name = "Nowhere, Atlantis", latitude = null, longitude = null)
            itineraryRepository.items.value = listOf(lost)
            val viewModel = viewModel()
            testScheduler.advanceUntilIdle()
            assertThat(locator.calls.map { it.second }).containsExactly("Nowhere, Atlantis")

            val bada = Fixtures.itineraryItem(tripId = TRIP_ID, name = BADA, latitude = null, longitude = null)
            locator.answer(BADA, 26.8696, 80.9135)
            itineraryRepository.save(bada)
            testScheduler.advanceUntilIdle()

            assertThat(locator.calls.map { it.second }).containsExactly("Nowhere, Atlantis", BADA).inOrder()
            assertThat(
                itineraryRepository.items.value
                    .first { it.id == bada.id }
                    .latitude,
            ).isEqualTo(26.8696)
            assertThat(viewModel.locating.value).isNull()
        }

    private companion object {
        const val RUMI = "Rumi Darwaza, 17/11 Hussainabad Road, Lucknow, Uttar Pradesh 226003"
        const val BADA = "Bada Imambara (Main Building), Machchhi Bhavan, Lucknow, Uttar Pradesh 226003"
    }
}
