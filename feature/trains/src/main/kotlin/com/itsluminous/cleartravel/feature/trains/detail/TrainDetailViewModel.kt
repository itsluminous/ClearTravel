package com.itsluminous.cleartravel.feature.trains.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.trains.journeyDuration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/**
 * One itinerary leg this ticket is part of (ADR-028): the trip to open plus the day
 * it sits on. [dayIndex] is 0-based like the itinerary ("Day 1" = 0).
 */
data class LinkedTrip(
    val tripId: String,
    val tripName: String,
    val dayIndex: Int,
)

data class TrainDetailUiState(
    val ticket: TrainTicket? = null,
    val passengers: List<TrainPassenger> = emptyList(),
    val routeStops: List<TrainRouteStop> = emptyList(),
    val loading: Boolean = true,
    /** Trips whose itinerary links this ticket, in (day, order) sequence; empty = none. */
    val linkedTrips: List<LinkedTrip> = emptyList(),
) {
    /** Derived from the route stops when both end times parse; null otherwise. */
    val duration: Duration? get() = journeyDuration(routeStops)
}

/**
 * Backs the ticket detail bottom sheet. Reads Room only; the interactive PNR check
 * writes through [applyStatusResult] and the observed flows pick the change up.
 * [ItineraryRepository]/[TripRepository] are read-only here — the reverse lookup that
 * renders the "Part of" rows (ADR-028).
 */
@HiltViewModel
class TrainDetailViewModel
    @Inject
    constructor(
        private val repository: TrainRepository,
        private val itineraryRepository: ItineraryRepository,
        private val tripRepository: TripRepository,
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
                            linkedTrips(id),
                        ) { ticket, passengers, stops, linked ->
                            TrainDetailUiState(
                                ticket = ticket,
                                passengers = passengers,
                                routeStops = stops,
                                loading = false,
                                linkedTrips = linked,
                            )
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = TrainDetailUiState(),
                )

        /** Linking legs joined with their (live) trips; legs of deleted/missing trips drop out. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun linkedTrips(id: String): Flow<List<LinkedTrip>> =
            itineraryRepository.observeItemsLinkedToJourney(id).flatMapLatest { items ->
                val tripIds = items.map(ItineraryItem::tripId).distinct()
                if (tripIds.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(tripIds.map(tripRepository::observeTrip)) { trips ->
                        val byId = trips.filterNotNull().associateBy(Trip::id)
                        items.mapNotNull { item ->
                            byId[item.tripId]?.let { trip -> LinkedTrip(trip.id, trip.name, item.dayIndex) }
                        }
                    }
                }
            }

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
