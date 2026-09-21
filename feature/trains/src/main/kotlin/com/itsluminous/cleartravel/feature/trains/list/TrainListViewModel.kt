package com.itsluminous.cleartravel.feature.trains.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainTicket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Which list the Trains segment is showing. */
enum class TrainListFilter { ACTIVE, ARCHIVED }

/** One card on the list: the ticket plus its live passengers (for status chips). */
data class TrainTicketCard(
    val ticket: TrainTicket,
    val passengers: List<TrainPassenger>,
    /**
     * True when a route is stored in Room — drives the card's route action:
     * offline route page when true, the WebView fetch flow when false (ADR-019).
     */
    val hasRoute: Boolean = false,
    /** Scheduled departure (`HH:mm`) from the first stored route stop, when known. */
    val departureTime: String? = null,
)

data class TrainListUiState(
    val filter: TrainListFilter = TrainListFilter.ACTIVE,
    val cards: List<TrainTicketCard> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && cards.isEmpty()
}

/** Backs the tickets list: reads Room only (offline-first), never a provider. */
@HiltViewModel
class TrainListViewModel
    @Inject
    constructor(
        private val repository: TrainRepository,
    ) : ViewModel() {
        private val filter = MutableStateFlow(TrainListFilter.ACTIVE)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TrainListUiState> =
            filter
                .flatMapLatest { current ->
                    val tickets =
                        when (current) {
                            TrainListFilter.ACTIVE -> repository.observeActive()
                            TrainListFilter.ARCHIVED -> repository.observeArchived()
                        }
                    tickets.flatMapLatest { list -> cardsFor(list) }.map { cards -> current to cards }
                }.map { (current, cards) ->
                    TrainListUiState(filter = current, cards = cards, loading = false)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = TrainListUiState(),
                )

        fun setFilter(value: TrainListFilter) {
            filter.value = value
        }

        private fun cardsFor(tickets: List<TrainTicket>): Flow<List<TrainTicketCard>> {
            if (tickets.isEmpty()) return flowOf(emptyList())
            val perTicket =
                tickets.map { ticket ->
                    combine(
                        repository.observePassengers(ticket.id),
                        repository.observeRouteStops(ticket.id),
                    ) { passengers, stops ->
                        TrainTicketCard(
                            ticket = ticket,
                            passengers = passengers,
                            hasRoute = stops.isNotEmpty(),
                            departureTime = departureTime(stops),
                        )
                    }
                }
            return combine(perTicket) { it.toList() }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
