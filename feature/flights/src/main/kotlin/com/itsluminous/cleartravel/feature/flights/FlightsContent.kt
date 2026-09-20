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
    ) : FlightsRoute

    data class StatusCheck(
        val flightId: String,
    ) : FlightsRoute

    data class PassViewer(
        val path: String,
    ) : FlightsRoute
}

/**
 * The Flights segment of the Journeys tab. The app module places this next to the
 * Trains segment under a segmented control; this module never references
 * `feature:trains`.
 *
 * [initialFlightId] is the notification deep-link hook (integration contract): when
 * non-null the list opens with that flight's detail sheet expanded. Defaulted so
 * existing call sites are untouched.
 */
@Composable
fun FlightsContent(
    modifier: Modifier = Modifier,
    initialFlightId: String? = null,
) {
    var route by remember { mutableStateOf<FlightsRoute>(FlightsRoute.Journeys) }
    // Transient (per D2 decision — no DB column): outcome of the last completed
    // status-check attempt, surfaced as a snackbar + detail-sheet line on return.
    var checkOutcome by remember { mutableStateOf<CheckOutcome?>(null) }
    val context = LocalContext.current

    // Feature-local WorkManager wiring: make sure a poll chain exists (ADR-013).
    LaunchedEffect(Unit) {
        FlightPollScheduler.ensureScheduled(context, nextDeparture = null)
    }

    LaunchedEffect(initialFlightId) {
        if (initialFlightId != null) route = FlightsRoute.Journeys
    }

    when (val current = route) {
        is FlightsRoute.Journeys ->
            FlightListScreen(
                onAddManual = { route = FlightsRoute.Form() },
                onImportPass = { uri -> route = FlightsRoute.Form(importUri = uri) },
                onEdit = { id -> route = FlightsRoute.Form(editId = id) },
                onCheckStatus = { id ->
                    checkOutcome = null
                    route = FlightsRoute.StatusCheck(id)
                },
                onViewPass = { path -> route = FlightsRoute.PassViewer(path) },
                modifier = modifier,
                initialDetailFlightId = initialFlightId,
                lastCheckOutcome = checkOutcome,
            )

        is FlightsRoute.Form ->
            FlightFormScreen(
                editId = current.editId,
                importUri = current.importUri,
                onClose = { route = FlightsRoute.Journeys },
                onSavedAndCheck = { id ->
                    checkOutcome = null
                    route = FlightsRoute.StatusCheck(id)
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
                onClose = { route = FlightsRoute.Journeys },
                modifier = modifier,
            )
    }
}
