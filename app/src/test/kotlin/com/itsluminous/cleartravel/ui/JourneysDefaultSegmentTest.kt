package com.itsluminous.cleartravel.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * ADR-049: the Journeys tab opens on Flights ONLY for a user with flights and no train
 * ticket at all; every other state (empty app, any live train — archived included)
 * keeps the Trains default.
 */
class JourneysDefaultSegmentRuleTest {
    @Test
    fun `no trains but a flight opens on flights`() {
        assertThat(JourneysDefaultSegmentRule.decide(liveTrainTickets = 0, liveFlights = 1)).isEqualTo(JourneysSegment.FLIGHTS)
        assertThat(JourneysDefaultSegmentRule.decide(liveTrainTickets = 0, liveFlights = 7)).isEqualTo(JourneysSegment.FLIGHTS)
    }

    @Test
    fun `any train keeps trains`() {
        assertThat(JourneysDefaultSegmentRule.decide(liveTrainTickets = 1, liveFlights = 0)).isEqualTo(JourneysSegment.TRAINS)
        assertThat(JourneysDefaultSegmentRule.decide(liveTrainTickets = 1, liveFlights = 5)).isEqualTo(JourneysSegment.TRAINS)
    }

    @Test
    fun `empty app keeps trains`() {
        assertThat(JourneysDefaultSegmentRule.decide(liveTrainTickets = 0, liveFlights = 0)).isEqualTo(JourneysSegment.TRAINS)
    }
}

/** The repository-backed decision: active + archived counted together, tombstones ignored, one-shot. */
class JourneysDefaultSegmentViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val trains = FakeTrainRepository()
    private val flights = FakeFlightRepository()

    private fun viewModel() = JourneysDefaultSegmentViewModel(trains, flights)

    @Test
    fun `undecided until asked, then flights for a flights-only user`() =
        runTest {
            flights.save(Fixtures.flightJourney())
            val viewModel = viewModel()
            assertThat(viewModel.defaultSegment.value).isNull()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.FLIGHTS)
        }

    @Test
    fun `an active train keeps trains even with flights`() =
        runTest {
            trains.save(Fixtures.trainTicket())
            flights.save(Fixtures.flightJourney())
            val viewModel = viewModel()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.TRAINS)
        }

    @Test
    fun `archived-only trains still count as having trains`() =
        runTest {
            trains.save(Fixtures.trainTicket(archived = true))
            flights.save(Fixtures.flightJourney())
            val viewModel = viewModel()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.TRAINS)
        }

    @Test
    fun `an archived flight is enough for the flights default`() =
        runTest {
            flights.save(Fixtures.flightJourney(archived = true))
            val viewModel = viewModel()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.FLIGHTS)
        }

    @Test
    fun `tombstoned trains are ignored`() =
        runTest {
            trains.save(Fixtures.trainTicket(deletedAt = Fixtures.NOW))
            trains.save(Fixtures.trainTicket(archived = true, deletedAt = Fixtures.NOW))
            flights.save(Fixtures.flightJourney())
            val viewModel = viewModel()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.FLIGHTS)
        }

    @Test
    fun `tombstoned flights do not count`() =
        runTest {
            flights.save(Fixtures.flightJourney(deletedAt = Fixtures.NOW))
            val viewModel = viewModel()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.TRAINS)
        }

    @Test
    fun `empty app opens on trains`() =
        runTest {
            val viewModel = viewModel()

            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.TRAINS)
        }

    @Test
    fun `the decision is made once - a train added later does not re-flip it`() =
        runTest {
            flights.save(Fixtures.flightJourney())
            val viewModel = viewModel()
            viewModel.decideOnce()
            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.FLIGHTS)

            trains.save(Fixtures.trainTicket())
            viewModel.decideOnce()

            assertThat(viewModel.defaultSegment.value).isEqualTo(JourneysSegment.FLIGHTS)
        }
}
