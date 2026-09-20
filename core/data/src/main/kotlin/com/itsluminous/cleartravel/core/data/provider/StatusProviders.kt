package com.itsluminous.cleartravel.core.data.provider

/**
 * Pluggable live-status provider contracts (skeleton stubs — the real request/result
 * types land with the Trains and Flights milestones). Both providers follow the same
 * pattern: the in-app WebView scrape provider is the DEFAULT implementation and an
 * API-backed provider (user-supplied key) is an optional alternative, bound via Hilt
 * so a mock/manual implementation always exists and the ticket UI never blocks on a
 * provider.
 */
interface TrainStatusProvider {
    /** Stable id used to select/report the active provider in Settings. */
    val providerId: String
}

/** See [TrainStatusProvider] — the flight counterpart (status, times, terminal/gate, belt). */
interface FlightStatusProvider {
    /** Stable id used to select/report the active provider in Settings. */
    val providerId: String
}
