package com.itsluminous.cleartravel.feature.flights.checkin

import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import java.time.Instant

/**
 * What the flight card's web check-in quick action should do (ADR-039 part A). The
 * card gates the tap on the airline's check-in window (`checkin-windows.json`,
 * [computeCheckInWindow]) instead of blindly opening a page that will refuse the
 * passenger.
 */
sealed interface CheckInGateDecision {
    /** Open [url] — inside the window, or no schedule to gate on (best effort, as before). */
    data class Open(
        val url: String,
    ) : CheckInGateDecision

    /** Too early: check-in opens at [opensAt]. */
    data class NotYetOpen(
        val opensAt: Instant,
    ) : CheckInGateDecision

    /** Window passed but the flight has not left: web check-in closed at [closedAt]. */
    data class Closed(
        val closedAt: Instant,
    ) : CheckInGateDecision

    /** The flight already departed (by status, or by the clock passing its departure). */
    data object Departed : CheckInGateDecision
}

/**
 * PURE gate decision. Order of precedence: departed → not yet open → closed → open.
 * "Departed" is true when the status says so ([FlightStatus.DEPARTED]/[FlightStatus.LANDED])
 * or [now] is past the best-known departure (`estDep ?: schedDep`). Without a
 * scheduled departure there is no window to gate on, so the URL opens as before.
 * [fallbackUrl] is used when the flight carries no airline check-in URL.
 */
fun decideCheckInGate(
    flight: FlightJourney,
    rules: CheckInRules,
    now: Instant,
    fallbackUrl: String,
): CheckInGateDecision {
    val url = flight.checkInUrl ?: fallbackUrl
    val departure = flight.estDep ?: flight.schedDep
    val departedByStatus = flight.status == FlightStatus.DEPARTED || flight.status == FlightStatus.LANDED
    if (departedByStatus || (departure != null && !now.isBefore(departure))) return CheckInGateDecision.Departed
    val window = computeCheckInWindow(rules, flight.airlineIata, flight.schedDep) ?: return CheckInGateDecision.Open(url)
    return when {
        now.isBefore(window.opensAt) -> CheckInGateDecision.NotYetOpen(window.opensAt)
        !now.isBefore(window.closesAt) -> CheckInGateDecision.Closed(window.closesAt)
        else -> CheckInGateDecision.Open(url)
    }
}
