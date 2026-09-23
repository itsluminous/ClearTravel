package com.itsluminous.cleartravel.feature.itinerary.share

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.share.ShareDecodeResult
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.TripSharePayload
import com.itsluminous.cleartravel.core.model.EntityIds
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeItineraryRepository
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakeTripRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** ADR-039: the trip share link carries the trip + its items under their original ids. */
class TripShareViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trips = FakeTripRepository()
    private val itinerary = FakeItineraryRepository()
    private val viewModel = TripShareViewModel(trips, itinerary)

    @Test
    fun `ready outcome carries a decodable link with the trip's ids and stripped journey links`() =
        runTest {
            val trip = trips.save(Fixtures.trip(name = "Tokyo"))
            val a = itinerary.save(Fixtures.itineraryItem(tripId = trip.id, dayIndex = 0, name = "Senso-ji"))
            val b =
                itinerary.save(
                    Fixtures.itineraryItem(
                        tripId = trip.id,
                        dayIndex = 1,
                        name = "Kyoto",
                        linkedJourneyId = "j",
                        linkedJourneyType = JourneyType.TRAIN,
                    ),
                )
            itinerary.save(Fixtures.itineraryItem(tripId = "other-trip", name = "Elsewhere"))

            val outcome = viewModel.buildOutcome(trip.id) as TripShareOutcome.Ready

            assertThat(outcome.tripName).isEqualTo("Tokyo")
            val payload = (ShareLinkCodec.decode(outcome.url) as ShareDecodeResult.Ok).payload as TripSharePayload
            assertThat(payload.id).isEqualTo(trip.id)
            assertThat(payload.items.map { it.id }).containsExactly(a.id, b.id).inOrder()
            assertThat(outcome.url).doesNotContain("Elsewhere")
        }

    @Test
    fun `missing trip and oversized trip are reported, not shared`() =
        runTest {
            assertThat(viewModel.buildOutcome("nope")).isEqualTo(TripShareOutcome.Missing)

            val huge = trips.save(Fixtures.trip(name = "Everything"))
            repeat(400) { index ->
                itinerary.save(
                    Fixtures.itineraryItem(
                        tripId = huge.id,
                        dayIndex = index / 10,
                        orderInDay = index % 10,
                        name = "Place ${EntityIds.newId()}",
                        note = "Note ${EntityIds.newId()}",
                    ),
                )
            }
            assertThat(viewModel.buildOutcome(huge.id)).isEqualTo(TripShareOutcome.TooLong)
        }
}
