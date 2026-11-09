package com.itsluminous.cleartravel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The two segments of the Journeys tab, in display order (Trains first). */
enum class JourneysSegment {
    TRAINS,
    FLIGHTS,
}

/**
 * ADR-049: the PURE rule for the segment the Journeys tab opens on when nothing has
 * chosen one — no saved manual pick, no deep link / landing. Trains, as it always was,
 * unless the user has NO train ticket at all but at least one flight: then a
 * trains-first tab would greet a flights-only traveller with an empty list every time.
 *
 * Counts are of LIVE (non-tombstoned) journeys, active AND archived together: a user
 * whose only train tickets are archived still "has trains" and keeps the Trains
 * default — archiving is tidying, not leaving the railways. An empty app (no trains,
 * no flights) stays on Trains exactly as before.
 */
object JourneysDefaultSegmentRule {
    fun decide(
        liveTrainTickets: Int,
        liveFlights: Int,
    ): JourneysSegment = if (liveTrainTickets == 0 && liveFlights > 0) JourneysSegment.FLIGHTS else JourneysSegment.TRAINS

    /**
     * [decide] over the FIRST emission of the repositories' active + archived lists
     * (the repositories already exclude tombstoned rows). A one-shot read on purpose:
     * the default is decided once and never re-flipped by later writes. A failing read
     * falls back to Trains rather than leaving the tab undecided.
     */
    suspend fun decide(
        trains: TrainRepository,
        flights: FlightRepository,
    ): JourneysSegment =
        combine(
            trains.observeActive(),
            trains.observeArchived(),
            flights.observeActive(),
            flights.observeArchived(),
        ) { activeTrains, archivedTrains, activeFlights, archivedFlights ->
            decide(
                liveTrainTickets = activeTrains.size + archivedTrains.size,
                liveFlights = activeFlights.size + archivedFlights.size,
            )
        }.catch { emit(JourneysSegment.TRAINS) }
            .first()
}

/**
 * Holds the Journeys tab's decided default segment for the life of the activity (one
 * per process in practice — `by viewModels()` in the activity survives rotation, tab
 * switches and the external-entry round trips that re-create the shell's NavHost).
 * [defaultSegment] is null until [decideOnce] has read Room; the tab holds its
 * content until then so it never flickers Trains → Flights on open (a local Room read,
 * so effectively instant). The decision is made ONCE: adding the first train later in
 * the session must not yank a user off the Flights list they are looking at, and a
 * segment the user tapped is kept by the tab itself, not here.
 */
@HiltViewModel
class JourneysDefaultSegmentViewModel
    @Inject
    constructor(
        private val trainRepository: TrainRepository,
        private val flightRepository: FlightRepository,
    ) : ViewModel() {
        private val mutableDefaultSegment = MutableStateFlow<JourneysSegment?>(null)

        /** Null while undecided; then the segment the tab opens on, fixed for this ViewModel's life. */
        val defaultSegment: StateFlow<JourneysSegment?> = mutableDefaultSegment.asStateFlow()

        private var decisionStarted = false

        /**
         * Starts the one-shot decision the first time it is called (from the Journeys
         * tab's first composition — i.e. after the app-lock gate, never before Room may
         * be touched). Every later call is a no-op, so the result stays put.
         */
        fun decideOnce() {
            if (decisionStarted) return
            decisionStarted = true
            viewModelScope.launch {
                mutableDefaultSegment.value = JourneysDefaultSegmentRule.decide(trainRepository, flightRepository)
            }
        }
    }
