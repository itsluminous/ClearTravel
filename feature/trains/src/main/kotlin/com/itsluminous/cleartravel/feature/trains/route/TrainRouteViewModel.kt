package com.itsluminous.cleartravel.feature.trains.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
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
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** One day-worth of route stops for the offline route page's section headers. */
data class RouteDaySection(
    /** 1-based running day (day 1 = departure day). */
    val day: Int,
    val stops: List<TrainRouteStop>,
)

data class TrainRouteUiState(
    val ticket: TrainTicket? = null,
    val stops: List<TrainRouteStop> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && stops.isEmpty()

    /** Stops grouped into day sections, in running order (ADR-019 multi-day UI). */
    val daySections: List<RouteDaySection>
        get() =
            stops
                .sortedBy(TrainRouteStop::sortOrder)
                .groupBy(TrainRouteStop::day)
                .toSortedMap()
                .map { (day, dayStops) -> RouteDaySection(day = day, stops = dayStops) }

    /** True when the route spans more than one running day — show day headers. */
    val isMultiDay: Boolean get() = daySections.size > 1

    /** Derived from the route stops when both end times parse; null otherwise. */
    val duration: Duration? get() = journeyDuration(stops)

    /**
     * When the stored route was fetched: the newest stop's `updatedAt` (every stop
     * in a `replaceRouteStops` batch shares one write stamp) — the ticket's own
     * `lastFetchedAt` tracks PNR checks, not route fetches.
     */
    val fetchedAt: Instant? get() = stops.maxOfOrNull(TrainRouteStop::updatedAt)
}

/**
 * Backs the OFFLINE route page (ADR-019): renders purely from Room via
 * [TrainRepository.observeRouteStops] — no network, no WebView. The refresh action
 * navigates to the [RouteFetchScreen] WebView flow, which writes to Room; this
 * screen picks the change up reactively.
 */
@HiltViewModel
class TrainRouteViewModel
    @Inject
    constructor(
        private val repository: TrainRepository,
    ) : ViewModel() {
        private val ticketId = MutableStateFlow<String?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<TrainRouteUiState> =
            ticketId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(TrainRouteUiState(loading = false))
                    } else {
                        combine(
                            repository.observeTicket(id),
                            repository.observeRouteStops(id),
                        ) { ticket, stops ->
                            TrainRouteUiState(ticket = ticket, stops = stops, loading = false)
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = TrainRouteUiState(),
                )

        fun setTicketId(id: String?) {
            ticketId.value = id
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
