package com.itsluminous.cleartravel.feature.flights.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInGateDecision
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.checkin.decideCheckInGate
import com.itsluminous.cleartravel.feature.flights.status.FlightStatusFallbacks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** Which list the chip row shows. */
enum class FlightListFilter { ACTIVE, ARCHIVED }

data class FlightListUiState(
    val flights: List<FlightJourney> = emptyList(),
    val filter: FlightListFilter = FlightListFilter.ACTIVE,
)

@HiltViewModel
class FlightListViewModel
    @Inject
    constructor(
        private val repository: FlightRepository,
        private val checkInRuleSource: CheckInRuleSource,
        private val clock: Clock,
    ) : ViewModel() {
        private val filter = MutableStateFlow(FlightListFilter.ACTIVE)

        val uiState: StateFlow<FlightListUiState> =
            combine(
                repository.observeActive(),
                repository.observeArchived(),
                filter,
            ) { active, archived, selected ->
                FlightListUiState(
                    flights = if (selected == FlightListFilter.ACTIVE) active else archived,
                    filter = selected,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FlightListUiState())

        fun setFilter(value: FlightListFilter) {
            filter.value = value
        }

        fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            viewModelScope.launch { repository.setArchived(id, archived) }
        }

        fun delete(id: String) {
            viewModelScope.launch { repository.delete(id) }
        }

        /**
         * ADR-039 part A: what the card's web check-in quick action should do right
         * now — open the airline page (window open / nothing to gate on), or tell the
         * user why not (opens later, closed, departed). Pure decision, data-file rules.
         */
        fun checkInGate(flight: FlightJourney): CheckInGateDecision =
            decideCheckInGate(
                flight = flight,
                rules = checkInRuleSource.load(),
                now = clock.instant(),
                fallbackUrl = FlightStatusFallbacks.checkInSearchUrl(flight.airlineIata),
            )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
