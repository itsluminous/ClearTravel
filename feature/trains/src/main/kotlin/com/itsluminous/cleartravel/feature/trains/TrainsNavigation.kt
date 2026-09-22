package com.itsluminous.cleartravel.feature.trains

import android.net.Uri

/** Where a form session got its initial content from. */
internal sealed interface FormEntry {
    data object Blank : FormEntry

    data class Edit(
        val ticketId: String,
    ) : FormEntry

    data class FromText(
        val text: String,
    ) : FormEntry

    data class FromUri(
        val uri: Uri,
    ) : FormEntry
}

/**
 * The Trains segment's internal navigation state (it is not a NavHost route).
 *
 * Because this is plain state rather than a back stack, system back has to be wired
 * explicitly — see [trainsBackTarget]. Screens that can be reached from more than one
 * place remember their opener ([SeatMap.fromDetail], [RouteView.fromDetail],
 * [RouteFetch.returnTo]) so a back press steps to where the user actually came from.
 */
internal sealed interface TrainsScreen {
    data object List : TrainsScreen

    data class Form(
        val entry: FormEntry,
    ) : TrainsScreen

    data class PnrCheck(
        val ticketId: String,
        val pnr: String,
    ) : TrainsScreen

    data class RouteFetch(
        val ticketId: String,
        val trainNumber: String,
        /**
         * The screen to return to when the fetch is closed or succeeds from the seat
         * map (ADR-022) or the offline route page; [List] lands on the offline route
         * page on success (ADR-019) and the list on close.
         */
        val returnTo: TrainsScreen = List,
        /**
         * Started hands-free off the first PNR check of a quick add (ADR-023 chain)
         * rather than by the user opening it. A chained fetch lands back on the LIST
         * on success (the "N stations loaded" snackbar tells the story); only an
         * explicitly opened fetch lands on the offline route page — see
         * [routeFetchLanding].
         */
        val chained: Boolean = false,
    ) : TrainsScreen

    /** The OFFLINE seat map (ADR-022) — coach strip + berth grid from Room. */
    data class SeatMap(
        val ticketId: String,
        val trainNumber: String,
        /** Opened from the detail sheet — back re-opens it rather than landing on the bare list. */
        val fromDetail: Boolean = false,
    ) : TrainsScreen

    /** The OFFLINE route page (ADR-019) — renders the stored route from Room. */
    data class RouteView(
        val ticketId: String,
        val trainNumber: String,
        /** Opened from the detail sheet — back re-opens it rather than landing on the bare list. */
        val fromDetail: Boolean = false,
    ) : TrainsScreen
}

/** The state the Trains segment should be in after ONE system-back step. */
internal data class TrainsBackTarget(
    val screen: TrainsScreen,
    /** The detail sheet to (re)open on the list, or null for the bare list. */
    val detailTicketId: String? = null,
)

/**
 * One sensible step back from the current Trains state, or null when the segment is
 * on its base list with nothing open (back then falls through to the app shell).
 *
 * - Detail sheet over the list → dismiss it.
 * - Form → cancel (nothing is written, same as the form's own Cancel).
 * - PNR check → the list (its Close semantics).
 * - Route fetch → wherever it was opened from (seat map / offline route page / list).
 * - Seat map and offline route page → the detail sheet when opened from it, else the list.
 */
internal fun trainsBackTarget(
    current: TrainsScreen,
    detailTicketId: String?,
): TrainsBackTarget? =
    when (current) {
        is TrainsScreen.List ->
            if (detailTicketId != null) TrainsBackTarget(TrainsScreen.List, detailTicketId = null) else null
        is TrainsScreen.Form -> TrainsBackTarget(TrainsScreen.List)
        is TrainsScreen.PnrCheck -> TrainsBackTarget(TrainsScreen.List)
        is TrainsScreen.RouteFetch -> TrainsBackTarget(current.returnTo)
        is TrainsScreen.SeatMap -> backToOpener(current.ticketId, current.fromDetail)
        is TrainsScreen.RouteView -> backToOpener(current.ticketId, current.fromDetail)
    }

private fun backToOpener(
    ticketId: String,
    fromDetail: Boolean,
): TrainsBackTarget = TrainsBackTarget(TrainsScreen.List, detailTicketId = ticketId.takeIf { fromDetail })

/**
 * Where the Trains segment lands once a route fetch has APPLIED its result.
 *
 * - Opened from the seat map (ADR-022) or the offline route page's refresh → back to
 *   that screen, which now renders the fresh data from Room.
 * - Chained off a quick add's first PNR check ([TrainsScreen.RouteFetch.chained]) →
 *   the list: the user never asked for the route page, so the chain closes where it
 *   started and the "N stations loaded" snackbar reports the outcome.
 * - Explicitly opened from the list / detail sheet (place pin) → the offline route
 *   page, so the freshly fetched route is immediately visible (ADR-019).
 */
internal fun routeFetchLanding(fetch: TrainsScreen.RouteFetch): TrainsScreen =
    when {
        fetch.returnTo is TrainsScreen.SeatMap || fetch.returnTo is TrainsScreen.RouteView -> fetch.returnTo
        fetch.chained -> TrainsScreen.List
        else -> TrainsScreen.RouteView(ticketId = fetch.ticketId, trainNumber = fetch.trainNumber)
    }
