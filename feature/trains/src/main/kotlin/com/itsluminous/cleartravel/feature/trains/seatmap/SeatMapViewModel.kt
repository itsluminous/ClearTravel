package com.itsluminous.cleartravel.feature.trains.seatmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainTicket
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One box in the coach-position strip. */
data class CoachStripItem(
    val code: String,
    /** 1-based position among passenger coaches; null for the engine. */
    val position: Int?,
    val isEngine: Boolean,
    /** The coach printed on the ticket (filled-primary in the strip). */
    val isTicketCoach: Boolean,
    /** The coach whose layout is currently shown (defaults to the ticket coach). */
    val isSelected: Boolean,
)

/** One passenger chip in the header: coach + parsed berth (null when unallotted). */
data class PassengerSeat(
    val name: String,
    val coach: String,
    val berthNumber: Int?,
    /** Raw `seatBerth` text, shown when no number parses (e.g. `WL 12`). */
    val rawSeat: String,
)

data class SeatMapUiState(
    val ticket: TrainTicket? = null,
    val passengers: List<TrainPassenger> = emptyList(),
    val coaches: List<TrainCoach> = emptyList(),
    /** The coach whose layout is shown: the user's pick, else the ticket coach. */
    val selectedCoachCode: String? = null,
    /** Layout for [selectedCoachCode]'s class (or the ticket class); null = no layout known. */
    val layout: SeatLayout? = null,
    val loading: Boolean = true,
) {
    val hasCoaches: Boolean get() = coaches.isNotEmpty()

    /** The coach printed on the ticket — the first passenger with a coach. */
    val ticketCoachCode: String? get() = ticketCoachOf(passengers)

    val passengerSeats: List<PassengerSeat>
        get() =
            passengers.map { passenger ->
                PassengerSeat(
                    name = passenger.name,
                    coach = passenger.coach.trim().uppercase(),
                    berthNumber = SeatBerthParser.berthNumber(passenger.seatBerth),
                    rawSeat = passenger.seatBerth.trim(),
                )
            }

    /**
     * Berth numbers to highlight on the shown layout: berths of passengers seated in
     * the selected coach (coach-less passengers count when the ticket has no coach
     * information at all — the layout shown is then the ticket class's).
     */
    val highlightedBerths: Set<Int>
        get() {
            val selected = selectedCoachCode?.trim()?.uppercase()
            return passengerSeats
                .filter { seat ->
                    when {
                        selected == null -> true
                        seat.coach.isEmpty() -> ticketCoachCode == null
                        else -> seat.coach == selected
                    }
                }.mapNotNull(PassengerSeat::berthNumber)
                .toSet()
        }

    /** The strip: engine (unnumbered) + coaches numbered 1..n in rake order. */
    val strip: List<CoachStripItem>
        get() {
            val ticketCoach = ticketCoachCode
            val selected = selectedCoachCode?.trim()?.uppercase()
            var position = 0
            return coaches.sortedBy(TrainCoach::sortOrder).map { coach ->
                val code = coach.code.trim().uppercase()
                val engine = isEngineCode(code)
                if (!engine) position += 1
                CoachStripItem(
                    code = code,
                    position = if (engine) null else position,
                    isEngine = engine,
                    isTicketCoach = ticketCoach != null && code == ticketCoach,
                    isSelected = selected != null && code == selected,
                )
            }
        }

    companion object {
        private val ENGINE_CODES = setOf("EN", "LOCO", "ENG")

        fun isEngineCode(code: String): Boolean = code.trim().uppercase() in ENGINE_CODES

        fun ticketCoachOf(passengers: List<TrainPassenger>): String? =
            passengers
                .sortedBy(TrainPassenger::sortOrder)
                .firstNotNullOfOrNull {
                    it.coach
                        .trim()
                        .uppercase()
                        .takeIf(String::isNotEmpty)
                }
    }
}

/**
 * Backs the seat-map screen (ADR-022): reads the ticket, its passengers and its
 * stored coach composition from Room only (offline-first — the strip and the map
 * render with no network; fetching coaches is the route-fetch WebView flow's job),
 * resolves the coach class via the pure [TrainClassResolver] (coach code first,
 * ticket class fallback) and expands its layout through the cached
 * [SeatLayoutCatalog]. [selectCoach] switches the shown layout to another coach in
 * the strip; the ticket coach stays marked.
 */
@HiltViewModel
class SeatMapViewModel
    @Inject
    constructor(
        private val repository: TrainRepository,
        private val catalog: SeatLayoutCatalog,
    ) : ViewModel() {
        private val ticketId = MutableStateFlow<String?>(null)
        private val userSelection = MutableStateFlow<String?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState: StateFlow<SeatMapUiState> =
            ticketId
                .flatMapLatest { id ->
                    if (id == null) {
                        flowOf(SeatMapUiState(loading = false))
                    } else {
                        combine(
                            repository.observeTicket(id),
                            repository.observePassengers(id),
                            repository.observeCoaches(id),
                            userSelection,
                        ) { ticket, passengers, coaches, selection ->
                            val selected = selection ?: SeatMapUiState.ticketCoachOf(passengers)
                            val classCode =
                                TrainClassResolver.resolve(
                                    coachCode = selected.orEmpty(),
                                    travelClass = ticket?.travelClass.orEmpty(),
                                )
                            SeatMapUiState(
                                ticket = ticket,
                                passengers = passengers,
                                coaches = coaches,
                                selectedCoachCode = selected,
                                layout = classCode?.let(catalog::layoutFor),
                                loading = false,
                            )
                        }
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = SeatMapUiState(),
                )

        fun setTicketId(id: String?) {
            if (ticketId.value != id) userSelection.value = null
            ticketId.value = id
        }

        /** Shows [code]'s class layout; the engine has no layout and is ignored. */
        fun selectCoach(code: String) {
            if (SeatMapUiState.isEngineCode(code)) return
            userSelection.value = code.trim().uppercase()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
