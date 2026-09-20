package com.itsluminous.cleartravel.feature.itinerary.form

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.itinerary.ItineraryMessage
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeFlightRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeItineraryRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTrainRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTripRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private const val TRIP_ID = "trip-1"

class ItineraryItemFormViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tripRepository = FakeTripRepository()
    private val itineraryRepository = FakeItineraryRepository()
    private val trainRepository = FakeTrainRepository()
    private val flightRepository = FakeFlightRepository()

    private fun viewModel(
        itemId: String? = null,
        dayIndex: Int = 0,
    ): ItineraryItemFormViewModel =
        ItineraryItemFormViewModel(
            savedStateHandle =
                SavedStateHandle(
                    mapOf("tripId" to TRIP_ID, "itemId" to itemId.orEmpty(), "dayIndex" to dayIndex),
                ),
            tripRepository = tripRepository,
            itineraryRepository = itineraryRepository,
            trainRepository = trainRepository,
            flightRepository = flightRepository,
        )

    @Test
    fun `editing loads the stored item into the form`() =
        runTest {
            val item =
                Fixtures.itineraryItem(
                    tripId = TRIP_ID,
                    name = "Beach",
                    category = PlaceCategory.ACTIVITY,
                    dayIndex = 2,
                    latitude = 15.0,
                    longitude = 74.0,
                )
            itineraryRepository.items.value = listOf(item)

            val viewModel = viewModel(itemId = item.id)

            assertThat(viewModel.isEdit).isTrue()
            val form = viewModel.form.value
            assertThat(form.name).isEqualTo("Beach")
            assertThat(form.category).isEqualTo(PlaceCategory.ACTIVITY)
            assertThat(form.dayIndex).isEqualTo(2)
            assertThat(form.latitude).isEqualTo(15.0)
        }

    @Test
    fun `saving a place without a name is rejected with a message`() =
        runTest {
            val viewModel = viewModel()

            viewModel.save()

            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.ITEM_NAME_REQUIRED)
            assertThat(viewModel.saved.value).isFalse()
            assertThat(itineraryRepository.items.value).isEmpty()
        }

    @Test
    fun `saving a commute without both endpoints is rejected`() =
        runTest {
            val viewModel = viewModel()
            viewModel.update { it.copy(type = ItineraryItemType.COMMUTE, fromName = "Airport") }

            viewModel.save()

            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.ITEM_ROUTE_REQUIRED)
            assertThat(itineraryRepository.items.value).isEmpty()
        }

    @Test
    fun `saving a place appends to the chosen day and derives its date`() =
        runTest {
            tripRepository.trips.value =
                listOf(Fixtures.trip(id = TRIP_ID, startDate = LocalDate.parse("2026-09-20")))
            itineraryRepository.items.value =
                listOf(Fixtures.itineraryItem(tripId = TRIP_ID, dayIndex = 1, orderInDay = 3))
            val viewModel = viewModel(dayIndex = 1)
            viewModel.update { it.copy(name = "Fort", latitude = 15.5, longitude = 73.8) }

            viewModel.save()

            assertThat(viewModel.saved.value).isTrue()
            val saved = itineraryRepository.items.value.first { it.name == "Fort" }
            assertThat(saved.dayIndex).isEqualTo(1)
            assertThat(saved.orderInDay).isEqualTo(4)
            assertThat(saved.date).isEqualTo(LocalDate.parse("2026-09-21"))
            assertThat(saved.latitude).isEqualTo(15.5)
        }

    @Test
    fun `saving a commute with a blank name derives it from the endpoints`() =
        runTest {
            val viewModel = viewModel()
            viewModel.update {
                it.copy(
                    type = ItineraryItemType.COMMUTE,
                    fromName = "Panjim",
                    toName = "Old Goa",
                    commuteMode = CommuteMode.BUS,
                )
            }

            viewModel.save()

            val saved = itineraryRepository.items.value.single()
            assertThat(saved.name).isEqualTo("Panjim - Old Goa")
            assertThat(saved.commuteMode).isEqualTo(CommuteMode.BUS)
            assertThat(saved.latitude).isNull()
        }

    @Test
    fun `journey candidates list active trains and flights`() =
        runTest {
            trainRepository.tickets.value = listOf(Fixtures.trainTicket(trainNumber = "12627"))
            flightRepository.flights.value = listOf(Fixtures.flightJourney(flightNumber = "2345"))

            viewModel().journeyCandidates.test {
                val candidates =
                    awaitItem().let { if (it.isEmpty) awaitItem() else it }
                assertThat(candidates.trains.map { it.trainNumber }).containsExactly("12627")
                assertThat(candidates.flights.map { it.flightNumber }).containsExactly("2345")
            }
        }

    @Test
    fun `linking a train sets id type mode and prefills blank endpoints`() =
        runTest {
            val ticket = Fixtures.trainTicket(fromStation = "SBC", toStation = "NDLS")
            val viewModel = viewModel()
            viewModel.update { it.copy(type = ItineraryItemType.COMMUTE) }

            viewModel.linkTrain(ticket)

            val form = viewModel.form.value
            assertThat(form.linkedJourneyId).isEqualTo(ticket.id)
            assertThat(form.linkedJourneyType).isEqualTo(JourneyType.TRAIN)
            assertThat(form.commuteMode).isEqualTo(CommuteMode.TRAIN)
            assertThat(form.fromName).isEqualTo("SBC")
            assertThat(form.toName).isEqualTo("NDLS")

            viewModel.save()

            val saved = itineraryRepository.items.value.single()
            assertThat(saved.linkedJourneyId).isEqualTo(ticket.id)
            assertThat(saved.linkedJourneyType).isEqualTo(JourneyType.TRAIN)
        }

    @Test
    fun `linking a flight keeps user-typed endpoints and unlinking clears the link`() =
        runTest {
            val flight = Fixtures.flightJourney(depAirport = "BLR", arrAirport = "DEL")
            val viewModel = viewModel()
            viewModel.update {
                it.copy(type = ItineraryItemType.COMMUTE, fromName = "Bengaluru T2", toName = "Delhi T3")
            }

            viewModel.linkFlight(flight)

            val linked = viewModel.form.value
            assertThat(linked.linkedJourneyType).isEqualTo(JourneyType.FLIGHT)
            assertThat(linked.commuteMode).isEqualTo(CommuteMode.FLIGHT)
            assertThat(linked.fromName).isEqualTo("Bengaluru T2")
            assertThat(linked.toName).isEqualTo("Delhi T3")

            viewModel.clearLinkedJourney()

            assertThat(viewModel.form.value.linkedJourneyId).isNull()
            assertThat(viewModel.form.value.linkedJourneyType).isNull()
        }
}
