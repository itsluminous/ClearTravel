package com.itsluminous.cleartravel.feature.flights.list

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.FakeFlightRepository
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInGateDecision
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

class FlightListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var repository: FakeFlightRepository
    private lateinit var viewModel: FlightListViewModel

    private val active = Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2345")
    private val archived = Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", archived = true)

    @Before
    fun setUp() {
        repository = FakeFlightRepository()
        repository.seed(active, archived)
        viewModel = FlightListViewModel(repository, FakeCheckInRuleSource(), clock)
    }

    private val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)

    @Test
    fun `check-in gate uses the data-file window and the injected clock`() {
        // Default window opens 48 h before departure: a flight 3 days out is not yet open.
        val later = active.copy(schedDep = Fixtures.NOW.plus(Duration.ofDays(3)), checkInUrl = "https://x/checkin")
        assertThat(viewModel.checkInGate(later))
            .isEqualTo(CheckInGateDecision.NotYetOpen(opensAt = later.schedDep!!.minus(Duration.ofHours(48))))

        val soon = active.copy(schedDep = Fixtures.NOW.plus(Duration.ofHours(5)), checkInUrl = "https://x/checkin")
        assertThat(viewModel.checkInGate(soon)).isEqualTo(CheckInGateDecision.Open("https://x/checkin"))

        // No airline URL → the web-search fallback carries the airline code.
        val noUrl = soon.copy(checkInUrl = null)
        val open = viewModel.checkInGate(noUrl) as CheckInGateDecision.Open
        assertThat(open.url).contains("6E")
    }

    @Test
    fun `active filter shows active flights only`() =
        runTest {
            viewModel.uiState.test {
                val state = awaitItemMatching { it.flights.isNotEmpty() }
                assertThat(state.filter).isEqualTo(FlightListFilter.ACTIVE)
                assertThat(state.flights.map { it.id }).containsExactly(active.id)
            }
        }

    @Test
    fun `archived filter switches the list`() =
        runTest {
            viewModel.uiState.test {
                awaitItemMatching { it.flights.isNotEmpty() }
                viewModel.setFilter(FlightListFilter.ARCHIVED)
                val state = awaitItemMatching { it.filter == FlightListFilter.ARCHIVED }
                assertThat(state.flights.map { it.id }).containsExactly(archived.id)
            }
        }

    @Test
    fun `archiving a flight moves it out of the active list`() =
        runTest {
            viewModel.uiState.test {
                awaitItemMatching { it.flights.isNotEmpty() }
                viewModel.setArchived(active.id, true)
                val state = awaitItemMatching { it.flights.isEmpty() }
                assertThat(state.flights).isEmpty()
            }
            assertThat(repository.getFlight(active.id)!!.archived).isTrue()
        }

    @Test
    fun `deleting a flight removes it from every list`() =
        runTest {
            viewModel.uiState.test {
                awaitItemMatching { it.flights.isNotEmpty() }
                viewModel.delete(active.id)
                awaitItemMatching { it.flights.isEmpty() }
            }
            assertThat(repository.getFlight(active.id)).isNull()
        }

    private suspend fun app.cash.turbine.TurbineTestContext<FlightListUiState>.awaitItemMatching(
        predicate: (FlightListUiState) -> Boolean,
    ): FlightListUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
