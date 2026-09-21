package com.itsluminous.cleartravel.feature.flights

/**
 * In-tab navigation of the Flights segment (the app shell owns no flight routes).
 *
 * Plain state rather than a back stack, so system back is wired explicitly — see
 * [flightsBackRoute].
 */
internal sealed interface FlightsRoute {
    data object Journeys : FlightsRoute

    data class Form(
        val editId: String? = null,
        val importUri: String? = null,
        /** Picked booking-confirmation file (third add path, ADR-017). */
        val bookingUri: String? = null,
    ) : FlightsRoute

    data class StatusCheck(
        val flightId: String,
    ) : FlightsRoute

    data class PassViewer(
        val path: String,
        /** Viewer reuse (ADR-017): booking confirmations carry their own title. */
        val titleRes: Int = R.string.flights_pass_viewer_title,
    ) : FlightsRoute
}

/**
 * The route one system-back step from [current], or null when the segment must NOT
 * handle back itself:
 *
 * - [FlightsRoute.Journeys] — the base list (its detail/add sheets dismiss on their
 *   own); back leaves the tab.
 * - [FlightsRoute.StatusCheck] — the screen owns back so the last check outcome
 *   travels with the close, exactly like its Close icon.
 * - Form → the list (its Close semantics: nothing is written); document viewer → the list.
 */
internal fun flightsBackRoute(current: FlightsRoute): FlightsRoute? =
    when (current) {
        is FlightsRoute.Journeys -> null
        is FlightsRoute.StatusCheck -> null
        is FlightsRoute.Form -> FlightsRoute.Journeys
        is FlightsRoute.PassViewer -> FlightsRoute.Journeys
    }
