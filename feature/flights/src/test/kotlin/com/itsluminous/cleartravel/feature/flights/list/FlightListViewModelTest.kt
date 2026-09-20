package com.itsluminous.cleartravel.feature.flights.list

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.flights.FakeFlightRepository
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

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
        viewModel = FlightListViewModel(repository)
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
