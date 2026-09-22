package com.itsluminous.cleartravel.ui

import androidx.lifecycle.ViewModel
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddRequest
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddRequestBus
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddResult
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.feature.flights.FlightsEntryResult
import com.itsluminous.cleartravel.feature.trains.TrainsEntryResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Shell-scoped coordinator of the "add a journey from an itinerary leg" hand-off
 * (ADR-028). The itinerary form posts on the [JourneyAddRequestBus]; the shell
 * observes [pendingRequest], lands on the Journeys tab in pick mode
 * (`JourneysDeepLink.forJourneyAdd`), and when the segment reports how the add
 * ended, [complete] answers the bus — BEFORE the shell returns to the Trips tab, so
 * the restored form already holds the result. The mapping itself is pure
 * ([JourneyPickRouting]) so it is unit-testable without Hilt.
 */
@HiltViewModel
class JourneyPickCoordinator
    @Inject
    constructor(
        private val bus: JourneyAddRequestBus,
    ) : ViewModel() {
        val pendingRequest: StateFlow<JourneyAddRequest?> = bus.pendingRequest

        /** Nonce of the request the shell has already landed on (ADR-029): a landing happens once per request. */
        private var landedNonce: Long? = null

        /**
         * Returns [request] the FIRST time it is offered, null afterwards. The pending
         * request is a StateFlow and the shell's landing effect re-runs on every
         * activity re-creation (rotation, theme change), so without this latch a
         * still-pending request would drop the user back into pick mode each time.
         */
        fun takeLanding(request: JourneyAddRequest?): JourneyAddRequest? {
            if (request == null || request.nonce == landedNonce) return null
            landedNonce = request.nonce
            return request
        }

        fun complete(result: JourneyAddResult) = bus.complete(result)
    }

/** Pure mapping of Journeys-segment outcomes onto bus results (ADR-028). */
object JourneyPickRouting {
    /**
     * A saved ticket is linked; a refused duplicate links the EXISTING ticket (the
     * one the user evidently meant); a cancel links nothing.
     */
    fun resultFor(
        request: JourneyAddRequest,
        result: TrainsEntryResult,
    ): JourneyAddResult =
        when (result) {
            is TrainsEntryResult.Saved -> JourneyAddResult.Added(request.nonce, JourneyType.TRAIN, result.ticketId)
            is TrainsEntryResult.DuplicatePnr ->
                JourneyAddResult.Added(request.nonce, JourneyType.TRAIN, result.existingTicketId)
            is TrainsEntryResult.Cancelled -> JourneyAddResult.Cancelled(request.nonce)
        }

    /** Flights mirror of the trains mapping. */
    fun resultFor(
        request: JourneyAddRequest,
        result: FlightsEntryResult,
    ): JourneyAddResult =
        when (result) {
            is FlightsEntryResult.Saved -> JourneyAddResult.Added(request.nonce, JourneyType.FLIGHT, result.flightId)
            is FlightsEntryResult.DuplicateFlight ->
                JourneyAddResult.Added(request.nonce, JourneyType.FLIGHT, result.existingFlightId)
            is FlightsEntryResult.Cancelled -> JourneyAddResult.Cancelled(request.nonce)
        }
}
