package com.itsluminous.cleartravel.feature.trains.seatmap

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SeatMapViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeTrainRepository(now = { Fixtures.NOW })
    private val catalog = SeatLayoutCatalog(FileSeatLayoutSource())

    private val ticket = Fixtures.trainTicket(travelClass = "3A")
    private val rake = listOf("EN", "GN", "S1", "S2", "B1", "B2", "A1", "GN")

    private fun seed(
        passengers: List<Pair<String, String>> = listOf("B2" to "32", "B2" to "B2 35", "A1" to "12"),
        coaches: List<String> = rake,
    ) {
        repository.seed(
            ticket,
            ticketPassengers =
                passengers.mapIndexed { index, (coach, seat) ->
                    Fixtures.trainPassenger(ticketId = ticket.id, name = "P$index", coach = coach, seatBerth = seat, sortOrder = index)
                },
            ticketCoaches = coaches.mapIndexed { index, code -> Fixtures.trainCoach(ticketId = ticket.id, code = code, sortOrder = index) },
        )
    }

    private fun viewModel(): SeatMapViewModel = SeatMapViewModel(repository, catalog).also { it.setTicketId(ticket.id) }

    @Test
    fun `defaults to the ticket coach and highlights that coach's berths only`() =
        runTest {
            seed()
            viewModel().uiState.test {
                val state = awaitItem().takeIf { !it.loading } ?: awaitItem()

                assertThat(state.selectedCoachCode).isEqualTo("B2")
                assertThat(state.layout?.classCode).isEqualTo("3A")
                assertThat(state.highlightedBerths).containsExactly(32, 35)
                assertThat(state.passengerSeats.map { it.coach to it.berthNumber })
                    .containsExactly("B2" to 32, "B2" to 35, "A1" to 12)
                    .inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `strip numbers passenger coaches from 1 and marks engine, ticket coach and selection`() =
        runTest {
            seed()
            viewModel().uiState.test {
                val strip = (awaitItem().takeIf { !it.loading } ?: awaitItem()).strip

                assertThat(strip.map { it.code }).isEqualTo(rake)
                assertThat(strip.first().isEngine).isTrue()
                assertThat(strip.first().position).isNull()
                assertThat(strip.drop(1).map { it.position }).isEqualTo((1..7).toList())
                assertThat(strip.single { it.isTicketCoach }.code).isEqualTo("B2")
                assertThat(strip.single { it.isSelected }.code).isEqualTo("B2")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selecting another coach switches the layout class and the highlighted berths`() =
        runTest {
            seed()
            val vm = viewModel()
            vm.uiState.test {
                awaitItem().takeIf { !it.loading } ?: awaitItem()

                vm.selectCoach("A1")
                val state = awaitItem()
                assertThat(state.selectedCoachCode).isEqualTo("A1")
                assertThat(state.layout?.classCode).isEqualTo("2A")
                assertThat(state.highlightedBerths).containsExactly(12)
                assertThat(state.strip.single { it.isSelected }.code).isEqualTo("A1")
                assertThat(state.strip.single { it.isTicketCoach }.code).isEqualTo("B2")

                vm.selectCoach("S1")
                val sleeper = awaitItem()
                assertThat(sleeper.layout?.classCode).isEqualTo("SL")
                assertThat(sleeper.highlightedBerths).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selecting the engine is ignored`() =
        runTest {
            seed()
            val vm = viewModel()
            vm.uiState.test {
                awaitItem().takeIf { !it.loading } ?: awaitItem()
                vm.selectCoach("EN")
                expectNoEvents()
                assertThat(vm.uiState.value.selectedCoachCode).isEqualTo("B2")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `without stored coaches the state is empty-strip but the ticket-class layout still resolves`() =
        runTest {
            seed(coaches = emptyList())
            viewModel().uiState.test {
                val state = awaitItem().takeIf { !it.loading } ?: awaitItem()

                assertThat(state.hasCoaches).isFalse()
                assertThat(state.strip).isEmpty()
                assertThat(state.layout?.classCode).isEqualTo("3A")
                assertThat(state.highlightedBerths).containsExactly(32, 35)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `coach-less passengers highlight on the ticket-class layout`() =
        runTest {
            seed(passengers = listOf("" to "7", "" to "WL 4"), coaches = emptyList())
            viewModel().uiState.test {
                val state = awaitItem().takeIf { !it.loading } ?: awaitItem()

                assertThat(state.selectedCoachCode).isNull()
                assertThat(state.layout?.classCode).isEqualTo("3A")
                assertThat(state.highlightedBerths).containsExactly(7, 4)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a fetched coach composition is picked up reactively`() =
        runTest {
            seed(coaches = emptyList())
            val vm = viewModel()
            vm.uiState.test {
                assertThat((awaitItem().takeIf { !it.loading } ?: awaitItem()).hasCoaches).isFalse()

                repository.replaceCoaches(
                    ticket.id,
                    listOf(Fixtures.trainCoach(code = "EN"), Fixtures.trainCoach(code = "B2", sortOrder = 1)),
                )

                val state = awaitItem()
                assertThat(state.hasCoaches).isTrue()
                assertThat(state.strip.map { it.code }).containsExactly("EN", "B2").inOrder()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `unknown class and unknown coach code yield no layout`() =
        runTest {
            repository.seed(
                Fixtures.trainTicket(id = ticket.id, travelClass = "ZZ"),
                ticketPassengers = listOf(Fixtures.trainPassenger(ticketId = ticket.id, coach = "PC", seatBerth = "3")),
            )
            viewModel().uiState.test {
                val state = awaitItem().takeIf { !it.loading } ?: awaitItem()
                assertThat(state.layout).isNull()
                assertThat(state.selectedCoachCode).isEqualTo("PC")
                cancelAndIgnoreRemainingEvents()
            }
        }
}
