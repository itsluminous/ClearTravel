package com.itsluminous.cleartravel.feature.itinerary.trips

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.itinerary.ItineraryMessage
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTripRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class TripsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTripRepository()

    // Constructed lazily so the ViewModel (and its viewModelScope) is created AFTER
    // MainDispatcherRule has swapped in the test main dispatcher.
    private val viewModel by lazy { TripsViewModel(repository) }

    @Test
    fun `active and archived trips come from the repository split`() =
        runTest {
            val active = Fixtures.trip(name = "Active trip")
            val archived = Fixtures.trip(name = "Archived trip", archived = true)
            repository.trips.value = listOf(active, archived)

            viewModel.activeTrips.test {
                val trips = awaitItem().ifEmpty { awaitItem() }
                assertThat(trips.map { it.name }).containsExactly("Active trip")
            }
            viewModel.archivedTrips.test {
                val trips = awaitItem().ifEmpty { awaitItem() }
                assertThat(trips.map { it.name }).containsExactly("Archived trip")
            }
        }

    @Test
    fun `saveTrip rejects a blank name with a message and persists nothing`() =
        runTest {
            val accepted =
                viewModel.saveTrip(
                    existing = null,
                    name = "  ",
                    destination = "Goa",
                    startDate = null,
                    endDate = null,
                    coverEmoji = "🏖️",
                    coverColor = "#FF0B57D0",
                )

            assertThat(accepted).isFalse()
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.TRIP_NAME_REQUIRED)
            assertThat(repository.trips.value).isEmpty()
        }

    @Test
    fun `saveTrip rejects an end date before the start date`() =
        runTest {
            val accepted =
                viewModel.saveTrip(
                    existing = null,
                    name = "Goa",
                    destination = "",
                    startDate = LocalDate.parse("2026-09-24"),
                    endDate = LocalDate.parse("2026-09-20"),
                    coverEmoji = "",
                    coverColor = "",
                )

            assertThat(accepted).isFalse()
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.TRIP_DATES_INVALID)
            assertThat(repository.trips.value).isEmpty()
        }

    @Test
    fun `saveTrip persists a valid new trip and keeps the edited trip id`() =
        runTest {
            val accepted =
                viewModel.saveTrip(
                    existing = null,
                    name = " Goa ",
                    destination = "Goa, India",
                    startDate = LocalDate.parse("2026-09-20"),
                    endDate = LocalDate.parse("2026-09-24"),
                    coverEmoji = "🏖️",
                    coverColor = "#FF006874",
                )

            assertThat(accepted).isTrue()
            val saved = repository.trips.value.single()
            assertThat(saved.name).isEqualTo("Goa")
            assertThat(saved.coverColor).isEqualTo("#FF006874")
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.TRIP_SAVED)

            viewModel.saveTrip(
                existing = saved,
                name = "Goa monsoon",
                destination = saved.destination,
                startDate = saved.startDate,
                endDate = saved.endDate,
                coverEmoji = saved.coverEmoji,
                coverColor = saved.coverColor,
            )

            val edited = repository.trips.value.single()
            assertThat(edited.id).isEqualTo(saved.id)
            assertThat(edited.name).isEqualTo("Goa monsoon")
        }

    @Test
    fun `deleteTrip removes the trip and reports it`() =
        runTest {
            val trip = Fixtures.trip()
            repository.trips.value = listOf(trip)

            viewModel.deleteTrip(trip.id)

            assertThat(repository.trips.value).isEmpty()
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.TRIP_DELETED)
        }

    @Test
    fun `setArchived toggles the flag and reports the right message`() =
        runTest {
            val trip = Fixtures.trip()
            repository.trips.value = listOf(trip)

            viewModel.setArchived(trip.id, archived = true)

            assertThat(
                repository.trips.value
                    .single()
                    .archived,
            ).isTrue()
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.TRIP_ARCHIVED)

            viewModel.setArchived(trip.id, archived = false)

            assertThat(
                repository.trips.value
                    .single()
                    .archived,
            ).isFalse()
            assertThat(viewModel.message.value).isEqualTo(ItineraryMessage.TRIP_UNARCHIVED)
        }
}
