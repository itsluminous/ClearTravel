package com.itsluminous.cleartravel.feature.flights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.cleartravel.feature.flights.form.FlightFormScreen
import com.itsluminous.cleartravel.feature.flights.list.DuplicateFlightNotice
import com.itsluminous.cleartravel.feature.flights.list.FlightListScreen
import com.itsluminous.cleartravel.feature.flights.pass.BoardingPassViewerScreen
import com.itsluminous.cleartravel.feature.flights.polling.FlightPollScheduler
import com.itsluminous.cleartravel.feature.flights.status.CheckOutcome
import com.itsluminous.cleartravel.feature.flights.status.StatusCheckScreen

/** In-tab navigation of the Flights segment (the app shell owns no flight routes). */
private sealed interface FlightsRoute {
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
 * The Flights segment of the Journeys tab. The app module places this next to the
 * Trains segment under a segmented control; this module never references
 * `feature:trains`.
 *
 * [initialFlightId] is the deep-link hook (integration contract): when non-null the
 * segment arrives on that journey per [initialAction] — detail sheet expanded
 * (notification deep links, saves) or the list with a duplicate notice whose "View"
 * opens the existing journey (ADR-025). Both defaulted so existing call sites are
 * untouched.
 */
@Composable
fun FlightsContent(
    modifier: Modifier = Modifier,
    initialFlightId: String? = null,
    initialAction: FlightsLandingAction = FlightsLandingAction.OPEN_DETAIL,
) {
    var route by remember { mutableStateOf<FlightsRoute>(FlightsRoute.Journeys) }
    // Transient (per D2 decision — no DB column): outcome of the last completed
    // status-check attempt, surfaced as a snackbar + detail-sheet line on return.
    var checkOutcome by remember { mutableStateOf<CheckOutcome?>(null) }
    // Transient: a refused duplicate save (ADR-025) — the list shows the notice with
    // a "View" action for the existing journey. Cleared whenever the list is left so
    // it never re-fires on a later return.
    var duplicateNotice by remember { mutableStateOf<DuplicateFlightNotice?>(null) }
    val context = LocalContext.current

    // Feature-local WorkManager wiring: make sure a poll chain exists (ADR-013).
    LaunchedEffect(Unit) {
        FlightPollScheduler.ensureScheduled(context, nextDeparture = null)
    }

    LaunchedEffect(initialFlightId, initialAction) {
        if (initialFlightId == null) return@LaunchedEffect
        route = FlightsRoute.Journeys
        if (initialAction == FlightsLandingAction.DUPLICATE_FLIGHT) {
            duplicateNotice = DuplicateFlightNotice(existingFlightId = initialFlightId)
        }
    }

    LaunchedEffect(route) {
        if (route !is FlightsRoute.Journeys) duplicateNotice = null
    }

    when (val current = route) {
        is FlightsRoute.Journeys ->
            FlightListScreen(
                onAddManual = { route = FlightsRoute.Form() },
                onImportPass = { uri -> route = FlightsRoute.Form(importUri = uri) },
                onImportBooking = { uri -> route = FlightsRoute.Form(bookingUri = uri) },
                onEdit = { id -> route = FlightsRoute.Form(editId = id) },
                onCheckStatus = { id ->
                    checkOutcome = null
                    route = FlightsRoute.StatusCheck(id)
                },
                onViewPass = { path -> route = FlightsRoute.PassViewer(path) },
                onViewBooking = { path ->
                    route = FlightsRoute.PassViewer(path, titleRes = R.string.flights_booking_viewer_title)
                },
                modifier = modifier,
                // A duplicate landing must NOT auto-open the sheet: a modal sheet would
                // cover the notice that explains why nothing was added.
                initialDetailFlightId = initialFlightId.takeIf { initialAction == FlightsLandingAction.OPEN_DETAIL },
                lastCheckOutcome = checkOutcome,
                duplicateNotice = duplicateNotice,
            )

        is FlightsRoute.Form ->
            FlightFormScreen(
                editId = current.editId,
                importUri = current.importUri,
                bookingUri = current.bookingUri,
                onClose = { route = FlightsRoute.Journeys },
                onSavedAndCheck = { id ->
                    checkOutcome = null
                    route = FlightsRoute.StatusCheck(id)
                },
                onDuplicate = { existingId ->
                    // Nothing was written (ADR-025): back to the list, which already
                    // shows the existing journey, with the notice + a "View" action.
                    duplicateNotice = DuplicateFlightNotice(existingFlightId = existingId)
                    route = FlightsRoute.Journeys
                },
                modifier = modifier,
            )

        is FlightsRoute.StatusCheck ->
            StatusCheckScreen(
                flightId = current.flightId,
                onClose = { outcome ->
                    checkOutcome = outcome
                    route = FlightsRoute.Journeys
                },
                modifier = modifier,
            )

        is FlightsRoute.PassViewer ->
            BoardingPassViewerScreen(
                path = current.path,
                titleRes = current.titleRes,
                onClose = { route = FlightsRoute.Journeys },
                modifier = modifier,
            )
    }
}
