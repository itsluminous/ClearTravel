package com.itsluminous.cleartravel.core.data.provider

import com.itsluminous.cleartravel.core.model.FlightStatus
import java.time.Instant
import java.time.LocalDate

/**
 * Pluggable live-status provider contracts (ADR-005). Both providers follow the same
 * pattern: the in-app WebView scrape provider is the DEFAULT implementation and an
 * API-backed provider (user-supplied key) is an optional alternative, bound via Hilt
 * so a mock/manual implementation always exists and the ticket UI never blocks on a
 * provider. Implementations land in `core:scrape` / feature modules; `core:data`
 * owns only the contract and result shapes.
 */
interface TrainStatusProvider {
    /** Stable id used to select/report the active provider in Settings. */
    val providerId: String

    /**
     * Fetches the current PNR status. May suspend for a long time (the WebView
     * provider waits for the user to submit/solve a captcha in the foreground).
     * Failures (parse error, network, user cancelled) come back as [Result.failure] —
     * callers surface them and keep showing the last stored data.
     */
    suspend fun fetchPnrStatus(pnr: String): Result<TrainStatusResult>
}

/** See [TrainStatusProvider] — the flight counterpart (status, times, terminal/gate, belt). */
interface FlightStatusProvider {
    /** Stable id used to select/report the active provider in Settings. */
    val providerId: String

    /**
     * Fetches the current status of flight `airlineIata`+`flightNumber` on [date]
     * (e.g. "6E", "2345"). Failures come back as [Result.failure].
     */
    suspend fun fetchFlightStatus(
        airlineIata: String,
        flightNumber: String,
        date: LocalDate,
    ): Result<FlightStatusResult>
}

/**
 * Parsed PNR status (ADR-005). Optional fields stay null/empty when the source page
 * doesn't expose them; `TrainRepository.applyStatusResult` merges only known values.
 */
data class TrainStatusResult(
    val pnr: String,
    /** Per-passenger statuses in on-ticket order (matched to passengers by position). */
    val passengers: List<TrainPassengerStatus>,
    /** Whether the reservation chart has been prepared; null = unknown. */
    val chartPrepared: Boolean? = null,
    val trainNumber: String = "",
    val trainName: String = "",
    /** Journey date from the result page; null = not reported. */
    val journeyDate: LocalDate? = null,
    /** Boarding station (the passenger's journey start, not the train origin). */
    val fromStation: String = "",
    /** Reserved-upto station (the passenger's journey end). */
    val toStation: String = "",
    val travelClass: String = "",
    val fetchedAt: Instant,
)

/** One passenger's status within a [TrainStatusResult]. Empty string = unknown. */
data class TrainPassengerStatus(
    /** Current (post-chart) status, e.g. "CNF", "RAC 4", "WL 12". */
    val currentStatus: String,
    /** Booking-time status when the page shows it. */
    val bookingStatus: String = "",
    val coach: String = "",
    val seatBerth: String = "",
)

/**
 * Parsed flight status (ADR-005). Null/empty fields mean "not reported by the
 * source"; `FlightRepository.applyStatusResult` merges only known values.
 */
data class FlightStatusResult(
    val status: FlightStatus,
    val schedDep: Instant? = null,
    val schedArr: Instant? = null,
    val estDep: Instant? = null,
    val estArr: Instant? = null,
    val depTerminal: String = "",
    val depGate: String = "",
    val arrTerminal: String = "",
    val arrGate: String = "",
    val baggageBelt: String = "",
    val aircraftType: String = "",
    val fetchedAt: Instant,
)
