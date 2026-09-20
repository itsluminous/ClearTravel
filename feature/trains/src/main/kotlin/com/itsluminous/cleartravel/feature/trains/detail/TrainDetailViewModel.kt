package com.itsluminous.cleartravel.feature.trains.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.feature.trains.journeyDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import javax.inject.Inject

data class TrainDetailUiState(
    val ticket: TrainTicket? = null,
    val passengers: List<TrainPassenger> = emptyList(),
    val routeStops: List<TrainRouteStop> = emptyList(),
    val loading: Boolean = true,
) {
    /** Derived from the route stops when both end times parse; null otherwise. */
    val duration: Duration? get() = journeyDuration(routeStops)
}

/**
 * Backs the ticket detail bottom sheet. Reads Room only; the interactive PNR check
 * writes through [applyStatusResult] and the observed flows pick the change up.
 */
@HiltViewModel
class TrainDetailViewModel
    @Inject
    constructor(
        private val repository: TrainRepository,
    ) : ViewModel() {
        private val ticketId = MutableStateFlow<String?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TrainDetailUiState> =
            ticketId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(TrainDetailUiState(loading = false))
                    } else {
                        combine(
                            repository.observeTicket(id),
                            repository.observePassengers(id),
                            repository.observeRouteStops(id),
                        ) { ticket, passengers, stops ->
                            TrainDetailUiState(
                                ticket = ticket,
                                passengers = passengers,
                                routeStops = stops,
                                loading = false,
                            )
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = TrainDetailUiState(),
                )

        fun setTicketId(id: String?) {
            ticketId.value = id
        }

        /**
         * Persists an interactive PNR-check result (ADR-005 merge semantics): the
         * repository updates per-passenger current status and the ticket's
         * `lastFetchedAt`, and [uiState] refreshes reactively.
         */
        fun applyStatusResult(result: TrainStatusResult) {
            val id = ticketId.value ?: return
            viewModelScope.launch { repository.applyStatusResult(id, result) }
        }

        fun setArchived(archived: Boolean) {
            val id = ticketId.value ?: return
            viewModelScope.launch { repository.setArchived(id, archived) }
        }

        fun delete() {
            val id = ticketId.value ?: return
            viewModelScope.launch { repository.delete(id) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
