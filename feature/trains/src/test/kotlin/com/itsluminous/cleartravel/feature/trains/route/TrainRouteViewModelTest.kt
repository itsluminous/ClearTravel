package com.itsluminous.cleartravel.feature.trains.route

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The OFFLINE route page's state (ADR-019): renders from Room only. */
class TrainRouteViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })

    private fun viewModel() = TrainRouteViewModel(repository)

    @Test
    fun `stored route renders offline with the ticket header`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(
                ticket,
                stops =
                    listOf(
                        Fixtures.trainRouteStop(
                            ticketId = ticket.id,
                            stationName = "Origin",
                            arrival = "",
                            departure = "15:20",
                            sortOrder = 0,
                        ),
                        Fixtures.trainRouteStop(
                            ticketId = ticket.id,
                            stationName = "Terminus",
                            arrival = "23:45",
                            departure = "",
                            sortOrder = 1,
                        ),
                    ),
            )
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.ticket?.id).isEqualTo(ticket.id)
                assertThat(state.stops.map { it.stationName }).containsExactly("Origin", "Terminus").inOrder()
                assertThat(state.isEmpty).isFalse()
            }
        }

    @Test
    fun `no stored route yields the empty (fetch prompt) state`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.isEmpty).isTrue()
                assertThat(state.daySections).isEmpty()
            }
        }

    @Test
    fun `stops group into ordered day sections for a multi-day route`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(
                ticket,
                stops =
                    listOf(
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "KOAA", day = 1, sortOrder = 0),
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "BBU", day = 1, sortOrder = 1),
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "DDU", day = 2, sortOrder = 2),
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "YJUD", day = 3, sortOrder = 3),
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "JAT", day = 3, sortOrder = 4),
                    ),
            )
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.isMultiDay).isTrue()
                assertThat(state.daySections.map { it.day }).containsExactly(1, 2, 3).inOrder()
                assertThat(state.daySections[0].stops.map { it.stationName }).containsExactly("KOAA", "BBU").inOrder()
                assertThat(state.daySections[2].stops.map { it.stationName }).containsExactly("YJUD", "JAT").inOrder()
            }
        }

    @Test
    fun `single-day route is not multi-day so no day headers are shown`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(
                ticket,
                stops =
                    listOf(
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "A", day = 1, sortOrder = 0),
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "B", day = 1, sortOrder = 1),
                    ),
            )
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.isMultiDay).isFalse()
                assertThat(state.daySections).hasSize(1)
            }
        }

    @Test
    fun `fetchedAt is the newest stop write stamp and duration derives from end times`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(
                ticket,
                stops =
                    listOf(
                        Fixtures.trainRouteStop(
                            ticketId = ticket.id,
                            stationName = "Origin",
                            arrival = "",
                            departure = "15:20",
                            day = 1,
                            sortOrder = 0,
                            updatedAt = Fixtures.NOW,
                        ),
                        Fixtures.trainRouteStop(
                            ticketId = ticket.id,
                            stationName = "Terminus",
                            arrival = "23:45",
                            departure = "",
                            day = 1,
                            sortOrder = 1,
                            updatedAt = Fixtures.NOW.plusSeconds(1),
                        ),
                    ),
            )
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.fetchedAt).isEqualTo(Fixtures.NOW.plusSeconds(1))
                assertThat(state.duration?.toMinutes()).isEqualTo(8 * 60 + 25)
            }
        }

    @Test
    fun `a fetch-flow write is picked up reactively (refresh round-trip)`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                assertThat(expectMostRecentItem().isEmpty).isTrue()

                // The RouteFetch WebView flow persists through the repository.
                repository.replaceRouteStops(
                    ticket.id,
                    listOf(
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "New A", sortOrder = 0),
                        Fixtures.trainRouteStop(ticketId = ticket.id, stationName = "New B", sortOrder = 1),
                    ),
                )

                val refreshed = expectMostRecentItem()
                assertThat(refreshed.stops.map { it.stationName }).containsExactly("New A", "New B").inOrder()
            }
        }
}
