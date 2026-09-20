package com.itsluminous.cleartravel.feature.trains.list

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class TrainListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })

    private fun viewModel() = TrainListViewModel(repository)

    @Test
    fun `empty repository yields the empty state`() =
        runTest {
            val vm = viewModel()

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.cards).isEmpty()
                assertThat(state.isEmpty).isTrue()
                assertThat(state.filter).isEqualTo(TrainListFilter.ACTIVE)
            }
        }

    @Test
    fun `active tickets appear as cards with their passengers`() =
        runTest {
            val ticket = Fixtures.trainTicket()
            val passengers =
                listOf(
                    Fixtures.trainPassenger(ticketId = ticket.id, name = "ASHA", sortOrder = 0),
                    Fixtures.trainPassenger(ticketId = ticket.id, name = "RAVI", sortOrder = 1),
                )
            repository.seed(ticket, passengers)
            val vm = viewModel()

            vm.uiState.test {
                val state = expectMostRecentItem()
                assertThat(state.cards).hasSize(1)
                val card = state.cards.single()
                assertThat(card.ticket.id).isEqualTo(ticket.id)
                assertThat(card.passengers.map { it.name }).containsExactly("ASHA", "RAVI").inOrder()
                assertThat(state.isEmpty).isFalse()
            }
        }

    @Test
    fun `archived tickets are hidden from the active list`() =
        runTest {
            repository.seed(Fixtures.trainTicket(archived = true))
            val vm = viewModel()

            vm.uiState.test {
                assertThat(expectMostRecentItem().cards).isEmpty()
            }
        }

    @Test
    fun `switching the filter shows the archived list`() =
        runTest {
            val active = Fixtures.trainTicket(trainNumber = "11111")
            val archived = Fixtures.trainTicket(trainNumber = "22222", archived = true)
            repository.seed(active)
            repository.seed(archived)
            val vm = viewModel()

            vm.uiState.test {
                assertThat(
                    expectMostRecentItem()
                        .cards
                        .single()
                        .ticket.trainNumber,
                ).isEqualTo("11111")

                vm.setFilter(TrainListFilter.ARCHIVED)

                val archivedState = expectMostRecentItem()
                assertThat(archivedState.filter).isEqualTo(TrainListFilter.ARCHIVED)
                assertThat(
                    archivedState.cards
                        .single()
                        .ticket.trainNumber,
                ).isEqualTo("22222")
            }
        }

    @Test
    fun `soft-deleted tickets never appear`() =
        runTest {
            repository.seed(Fixtures.trainTicket(deletedAt = Fixtures.NOW))
            val vm = viewModel()

            vm.uiState.test {
                assertThat(expectMostRecentItem().cards).isEmpty()
            }
        }
}
