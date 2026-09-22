package com.itsluminous.cleartravel.feature.itinerary.intake

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeItineraryRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTripRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** ADR-029 part D: shared Maps link → new or existing trip → one PLACE item on day 1. */
class MapsLinkIntakeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val tripRepository = FakeTripRepository()
    private val itineraryRepository = FakeItineraryRepository()

    /** Resolver the test completes by hand, so "resolving" is observable. */
    private val pendingResolution = CompletableDeferred<String?>()
    private val resolver =
        object : MapsLinkResolver {
            var calls = 0

            override suspend fun resolve(shortUrl: String): String? {
                calls++
                return pendingResolution.await()
            }
        }

    private fun viewModel() = MapsLinkIntakeViewModel(tripRepository, itineraryRepository, resolver)

    private val fullLink = "https://www.google.com/maps/place/Gateway+of+India/@18.92,72.83,17z/data=!3d18.921984!4d72.834654"

    @Test
    fun `a full link is parsed at once and defaults to a new trip when there are none`() =
        runTest {
            val viewModel = viewModel()

            viewModel.start("Look: $fullLink")

            val state = viewModel.state.value
            assertThat(state.active).isTrue()
            assertThat(state.resolving).isFalse()
            assertThat(state.place?.name).isEqualTo("Gateway of India")
            assertThat(state.target).isEqualTo(MapsIntakeTarget.NEW_TRIP)
            assertThat(resolver.calls).isEqualTo(0)
        }

    @Test
    fun `text without a maps link is ignored`() =
        runTest {
            val viewModel = viewModel()

            viewModel.start("PNR:1234567890 SBC-NDLS")

            assertThat(viewModel.state.value.active).isFalse()
        }

    @Test
    fun `with existing trips the first one is preselected as the target`() =
        runTest {
            tripRepository.trips.value = listOf(Fixtures.trip(id = "goa", name = "Goa"), Fixtures.trip(id = "hampi", name = "Hampi"))
            val viewModel = viewModel()

            viewModel.start(fullLink)

            assertThat(viewModel.state.value.target).isEqualTo(MapsIntakeTarget.EXISTING_TRIP)
            assertThat(viewModel.state.value.selectedTripId).isEqualTo("goa")
        }

    @Test
    fun `confirming into a new trip creates it and adds the place on day 1 with coordinates and link`() =
        runTest {
            val viewModel = viewModel()
            viewModel.start(fullLink)
            viewModel.setNewTripName("Mumbai weekend")

            viewModel.confirm(fallbackName = "Shared place")

            val trip = tripRepository.trips.value.single()
            assertThat(trip.name).isEqualTo("Mumbai weekend")
            val item = itineraryRepository.items.value.single()
            assertThat(item.tripId).isEqualTo(trip.id)
            assertThat(item.type).isEqualTo(ItineraryItemType.PLACE)
            assertThat(item.dayIndex).isEqualTo(0)
            assertThat(item.name).isEqualTo("Gateway of India")
            assertThat(item.latitude).isEqualTo(18.921984)
            assertThat(item.longitude).isEqualTo(72.834654)
            assertThat(item.link).isEqualTo(fullLink)
            assertThat(viewModel.state.value.addedToTripId).isEqualTo(trip.id)
        }

    @Test
    fun `a new trip without a typed name is named after the place`() =
        runTest {
            val viewModel = viewModel()
            viewModel.start(fullLink)

            viewModel.confirm(fallbackName = "Shared place")

            assertThat(
                tripRepository.trips.value
                    .single()
                    .name,
            ).isEqualTo("Gateway of India")
        }

    @Test
    fun `confirming into an existing trip appends after that trip's day-1 items and derives the date`() =
        runTest {
            tripRepository.trips.value = listOf(Fixtures.trip(id = "goa", name = "Goa", startDate = LocalDate.parse("2026-12-01")))
            itineraryRepository.items.value = listOf(Fixtures.itineraryItem(tripId = "goa", dayIndex = 0, orderInDay = 2))
            val viewModel = viewModel()
            viewModel.start("https://maps.google.com/?q=15.4989,73.8278")
            viewModel.selectTrip("goa")

            viewModel.confirm(fallbackName = "Shared place")

            val item = itineraryRepository.items.value.first { it.tripId == "goa" && it.link.isNotBlank() }
            assertThat(item.orderInDay).isEqualTo(3)
            assertThat(item.date).isEqualTo(LocalDate.parse("2026-12-01"))
            // No name in the link: the resource fallback names the place.
            assertThat(item.name).isEqualTo("Shared place")
            assertThat(tripRepository.trips.value).hasSize(1)
        }

    @Test
    fun `a short link is usable while resolving and refined when the redirect comes back`() =
        runTest {
            val viewModel = viewModel()

            viewModel.start("https://maps.app.goo.gl/AbC")

            assertThat(viewModel.state.value.resolving).isTrue()
            assertThat(viewModel.state.value.canConfirm(emptyList())).isFalse()
            assertThat(resolver.calls).isEqualTo(1)

            pendingResolution.complete(fullLink)

            val state = viewModel.state.value
            assertThat(state.resolving).isFalse()
            assertThat(state.place?.name).isEqualTo("Gateway of India")
            assertThat(state.place?.latitude).isEqualTo(18.921984)
            // The stored link stays the one the user shared.
            assertThat(state.place?.url).isEqualTo("https://maps.app.goo.gl/AbC")
            assertThat(state.canConfirm(emptyList())).isTrue()
        }

    @Test
    fun `a short link that cannot be resolved degrades to name unknown and no coordinates`() =
        runTest {
            val viewModel = viewModel()
            viewModel.start("https://maps.app.goo.gl/AbC")

            pendingResolution.complete(null)

            val state = viewModel.state.value
            assertThat(state.resolving).isFalse()
            assertThat(state.place?.name).isNull()
            assertThat(state.place?.hasLocation).isFalse()
            assertThat(state.canConfirm(emptyList())).isTrue()

            viewModel.confirm(fallbackName = "Shared place")

            val item = itineraryRepository.items.value.single()
            assertThat(item.name).isEqualTo("Shared place")
            assertThat(item.latitude).isNull()
            assertThat(item.link).isEqualTo("https://maps.app.goo.gl/AbC")
        }

    @Test
    fun `reset closes the intake`() =
        runTest {
            val viewModel = viewModel()
            viewModel.start(fullLink)

            viewModel.reset()

            assertThat(viewModel.state.value).isEqualTo(MapsIntakeState())
        }
}
