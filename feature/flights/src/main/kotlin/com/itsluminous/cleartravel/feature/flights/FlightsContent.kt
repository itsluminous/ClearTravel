package com.itsluminous.cleartravel.feature.flights

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/**
 * The Flights segment of the Journeys tab. The app module places this next to the
 * Trains segment under a segmented control; this module never references
 * `feature:trains`.
 *
 * [initialFlightId] is the deep-link hook (integration contract): when non-null the
 * segment arrives on that journey per [initialAction] — detail sheet expanded
 * (notification deep links, saves) or the list with a duplicate notice whose "View"
 * opens the existing journey (ADR-025). [addRequest] is the ADR-028 pick-mode hook:
 * the segment opens its add options at once and reports the outcome ONCE via
 * [onAddRequestDone] — saved → `Saved` (after "Save & check status" the report waits
 * for the check to close, like the external entry), refused duplicate →
 * `DuplicateFlight` (the existing journey is what the user meant), backed out
 * before a save → `Cancelled`. [onOpenTrip] reports a "Part of" row tapped in the
 * detail sheet. All defaulted so existing call sites are untouched.
 *
 * ADR-029 once-only landing: the landing effect is keyed on [landingNonce] too (two
 * consecutive links to the SAME journey both act) and reports [onLandingConsumed]
 * with that nonce as soon as it acts, so the host clears the landing and nothing
 * replays it when this segment is re-composed (tab revisit, segment toggle). The
 * detail-sheet landing is copied into segment-local state that is dropped whenever
 * the list is left, so returning from a form/check never re-opens the sheet either.
 */
@Composable
fun FlightsContent(
    modifier: Modifier = Modifier,
    initialFlightId: String? = null,
    initialAction: FlightsLandingAction = FlightsLandingAction.OPEN_DETAIL,
    landingNonce: Long? = null,
    onLandingConsumed: (nonce: Long) -> Unit = {},
    addRequest: FlightsAddRequest? = null,
    onAddRequestDone: (FlightsEntryResult) -> Unit = {},
    onOpenTrip: (tripId: String) -> Unit = {},
) {
    var route by remember { mutableStateOf<FlightsRoute>(FlightsRoute.Journeys) }
    // ADR-028 pick mode: the request being fulfilled, until its outcome is reported;
    // pickSavedFlightId bridges "Save & check status" to the check's close.
    var activeAddRequest by remember { mutableStateOf<FlightsAddRequest?>(null) }
    var pickSavedFlightId by remember { mutableStateOf<String?>(null) }
    val currentOnAddRequestDone by rememberUpdatedState(onAddRequestDone)

    /** Reports the pick outcome exactly once and leaves pick mode. */
    fun finishAddRequest(result: FlightsEntryResult) {
        if (activeAddRequest == null) return
        activeAddRequest = null
        pickSavedFlightId = null
        currentOnAddRequestDone(result)
    }

    // Transient (per D2 decision — no DB column): outcome of the last completed
    // status-check attempt, surfaced as a snackbar + detail-sheet line on return.
    var checkOutcome by remember { mutableStateOf<CheckOutcome?>(null) }
    // Transient: a refused duplicate save (ADR-025) — the list shows the notice with
    // a "View" action for the existing journey. Cleared whenever the list is left so
    // it never re-fires on a later return.
    var duplicateNotice by remember { mutableStateOf<DuplicateFlightNotice?>(null) }
    // Transient (ADR-029): the journey a landing asked to open the sheet for. Cleared
    // with the notice whenever the list is left, for the same never-re-fire reason.
    var landedDetailFlightId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // State-based navigation does not take part in system back by itself: without
    // this, back reaches the shell NavHost and pops the whole Journeys tab. Disabled
    // on the list (back leaves the tab) and on the status check, which owns its own
    // handler so the outcome travels back with it.
    val backRoute = flightsBackRoute(route)
    BackHandler(enabled = backRoute != null) {
        backRoute?.let {
            // Backing out of the add form in pick mode = the add was cancelled.
            if (route is FlightsRoute.Form) finishAddRequest(FlightsEntryResult.Cancelled)
            route = it
        }
    }

    // Feature-local WorkManager wiring: make sure a poll chain exists (ADR-013).
    LaunchedEffect(Unit) {
        FlightPollScheduler.ensureScheduled(context, nextDeparture = null)
    }

    LaunchedEffect(initialFlightId, initialAction, landingNonce) {
        if (initialFlightId == null) return@LaunchedEffect
        landingNonce?.let(onLandingConsumed)
        route = FlightsRoute.Journeys
        when (initialAction) {
            FlightsLandingAction.OPEN_DETAIL -> landedDetailFlightId = initialFlightId
            FlightsLandingAction.DUPLICATE_FLIGHT ->
                duplicateNotice = DuplicateFlightNotice(existingFlightId = initialFlightId)
        }
    }

    LaunchedEffect(route) {
        if (route !is FlightsRoute.Journeys) {
            duplicateNotice = null
            landedDetailFlightId = null
        }
    }

    LaunchedEffect(addRequest) {
        if (addRequest != null) {
            route = FlightsRoute.Journeys
            activeAddRequest = addRequest
        }
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
                initialDetailFlightId = landedDetailFlightId,
                lastCheckOutcome = checkOutcome,
                duplicateNotice = duplicateNotice,
                openAddSheetNonce = activeAddRequest?.nonce,
                onAddAbandoned = { finishAddRequest(FlightsEntryResult.Cancelled) },
                onOpenTrip = onOpenTrip,
            )

        is FlightsRoute.Form ->
            FlightFormScreen(
                editId = current.editId,
                importUri = current.importUri,
                bookingUri = current.bookingUri,
                onClose = {
                    finishAddRequest(FlightsEntryResult.Cancelled)
                    route = FlightsRoute.Journeys
                },
                onSaved = { id ->
                    route = FlightsRoute.Journeys
                    finishAddRequest(FlightsEntryResult.Saved(id))
                },
                onSavedAndCheck = { id ->
                    checkOutcome = null
                    if (activeAddRequest != null) pickSavedFlightId = id
                    route = FlightsRoute.StatusCheck(id)
                },
                onDuplicate = { existingId ->
                    route = FlightsRoute.Journeys
                    if (activeAddRequest != null) {
                        // Pick mode (ADR-028): the existing journey IS the one to link.
                        finishAddRequest(FlightsEntryResult.DuplicateFlight(existingId))
                    } else {
                        // Nothing was written (ADR-025): back to the list, which already
                        // shows the existing journey, with the notice + a "View" action.
                        duplicateNotice = DuplicateFlightNotice(existingFlightId = existingId)
                    }
                },
                modifier = modifier,
            )

        is FlightsRoute.StatusCheck ->
            StatusCheckScreen(
                flightId = current.flightId,
                onClose = { outcome ->
                    checkOutcome = outcome
                    route = FlightsRoute.Journeys
                    pickSavedFlightId?.let { finishAddRequest(FlightsEntryResult.Saved(it)) }
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
