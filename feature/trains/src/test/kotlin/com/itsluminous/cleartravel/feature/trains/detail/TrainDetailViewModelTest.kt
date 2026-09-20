package com.itsluminous.cleartravel.feature.trains.detail

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.provider.TrainPassengerStatus
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class TrainDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })

    private fun viewModel() = TrainDetailViewModel(repository)

    @Test
    fun `state emits the full ticket aggregate`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            val passenger = Fixtures.trainPassenger(ticketId = ticket.id)
            val stop = Fixtures.trainRouteStop(ticketId = ticket.id)
            repository.seed(ticket, listOf(passenger), listOf(stop))
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val loaded = expectMostRecentItem()
                assertThat(loaded.ticket?.id).isEqualTo(ticket.id)
                assertThat(loaded.passengers).hasSize(1)
                assertThat(loaded.routeStops).hasSize(1)
                assertThat(loaded.loading).isFalse()
            }
        }

    @Test
    fun `applying a status result updates last-fetched and passenger status in state`() =
        runTest {
            val ticket = Fixtures.trainTicket(lastFetchedAt = null)
            val passenger = Fixtures.trainPassenger(ticketId = ticket.id, currentStatus = "RAC 4")
            repository.seed(ticket, listOf(passenger))
            val vm = viewModel()
            vm.setTicketId(ticket.id)
            val fetchedAt = Instant.parse("2026-09-20T13:00:00Z")

            vm.uiState.test {
                assertThat(expectMostRecentItem().ticket?.lastFetchedAt).isNull()

                vm.applyStatusResult(
                    TrainStatusResult(
                        pnr = ticket.pnr,
                        passengers = listOf(TrainPassengerStatus(currentStatus = "CNF/B1/49")),
                        fetchedAt = fetchedAt,
                    ),
                )

                val updated = expectMostRecentItem()
                assertThat(updated.ticket?.lastFetchedAt).isEqualTo(fetchedAt)
                assertThat(updated.passengers.single().currentStatus).isEqualTo("CNF/B1/49")
            }
            assertThat(repository.appliedResults.single().first).isEqualTo(ticket.id)
        }

    @Test
    fun `archive toggle writes through the repository`() =
        runTest {
            val ticket = Fixtures.trainTicket(archived = false)
            repository.seed(ticket)
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.setArchived(true)

            assertThat(repository.getTicket(ticket.id)?.archived).isTrue()
        }

    @Test
    fun `delete soft-deletes and the state loses the ticket`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            repository.seed(ticket)
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                assertThat(expectMostRecentItem().ticket).isNotNull()

                vm.delete()

                assertThat(expectMostRecentItem().ticket).isNull()
            }
            assertThat(repository.deletedIds).containsExactly(ticket.id)
        }

    @Test
    fun `duration derives from route stops`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            val stops =
                listOf(
                    Fixtures.trainRouteStop(ticketId = ticket.id, arrival = "", departure = "17:00", day = 1, sortOrder = 0),
                    Fixtures.trainRouteStop(ticketId = ticket.id, arrival = "08:35", departure = "", day = 2, sortOrder = 1),
                )
            repository.seed(ticket, stops = stops)
            val vm = viewModel()
            vm.setTicketId(ticket.id)

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.duration).isNotNull()
                assertThat(state.duration!!.toHours()).isEqualTo(15)
            }
        }
}
