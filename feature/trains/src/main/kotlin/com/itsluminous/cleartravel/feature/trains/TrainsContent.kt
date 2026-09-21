package com.itsluminous.cleartravel.feature.trains

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.feature.trains.detail.TrainDetailSheet
import com.itsluminous.cleartravel.feature.trains.detail.TrainDetailViewModel
import com.itsluminous.cleartravel.feature.trains.form.TrainFormEvent
import com.itsluminous.cleartravel.feature.trains.form.TrainTicketFormScreen
import com.itsluminous.cleartravel.feature.trains.form.TrainTicketFormViewModel
import com.itsluminous.cleartravel.feature.trains.list.AddChoice
import com.itsluminous.cleartravel.feature.trains.list.TrainListScreen
import com.itsluminous.cleartravel.feature.trains.list.TrainListViewModel
import com.itsluminous.cleartravel.feature.trains.pnr.PnrCheckScreen
import com.itsluminous.cleartravel.feature.trains.route.RouteFetchScreen
import com.itsluminous.cleartravel.feature.trains.route.TrainRouteScreen
import com.itsluminous.cleartravel.feature.trains.seatmap.SeatMapScreen
import com.itsluminous.cleartravel.feature.trains.share.rememberTrainTicketSharer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the PNR-only landing waits for the saved ticket's card before falling back. */
private const val PNR_CHECK_LOOKUP_TIMEOUT_MILLIS = 3_000L

/**
 * Duplicate-PNR notice (ADR-024) over the list — the existing card is already on
 * screen — with a "View" action that opens its detail sheet. The sheet is NOT opened
 * automatically: a modal sheet would cover the snackbar and hide the explanation.
 */
private suspend fun showDuplicateNotice(
    snackbarHostState: SnackbarHostState,
    context: android.content.Context,
    onView: () -> Unit,
) {
    val result =
        snackbarHostState.showSnackbar(
            message = context.getString(R.string.trains_form_duplicate_pnr),
            actionLabel = context.getString(R.string.trains_form_duplicate_pnr_view),
            duration = SnackbarDuration.Long,
        )
    if (result == SnackbarResult.ActionPerformed) onView()
}

/** Where a form session got its initial content from. */
private sealed interface FormEntry {
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

/** The Trains segment's internal navigation state (it is not a NavHost route). */
private sealed interface TrainsScreen {
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
        /** True when opened from the seat map — a success returns there (ADR-022). */
        val returnToSeatMap: Boolean = false,
    ) : TrainsScreen

    /** The OFFLINE seat map (ADR-022) — coach strip + berth grid from Room. */
    data class SeatMap(
        val ticketId: String,
        val trainNumber: String,
    ) : TrainsScreen

    /** The OFFLINE route page (ADR-019) — renders the stored route from Room. */
    data class RouteView(
        val ticketId: String,
        val trainNumber: String,
    ) : TrainsScreen
}

/**
 * The Trains segment of the Journeys tab. The app module places this next to the
 * Flights segment under a segmented control; this module never references
 * `feature:flights`. Hosts the ticket list, add/edit form, detail bottom sheet and
 * the interactive PNR-check WebView screen behind an internal navigation state.
 *
 * [initialTicketId] is the deep-link hook (integration contract): when non-null the
 * segment arrives on that ticket per [initialAction] — detail sheet expanded
 * (notification deep links, saves), the PNR check opened (PNR-only quick add via a
 * share link, ADR-023) or the existing ticket shown with a duplicate notice
 * (ADR-024). Both defaulted so existing call sites are untouched.
 */
@Composable
fun TrainsContent(
    modifier: Modifier = Modifier,
    initialTicketId: String? = null,
    initialAction: TrainsLandingAction = TrainsLandingAction.OPEN_DETAIL,
) {
    var screen by remember { mutableStateOf<TrainsScreen>(TrainsScreen.List) }
    var detailTicketId by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val listViewModel: TrainListViewModel = hiltViewModel()
    val formViewModel: TrainTicketFormViewModel = hiltViewModel()
    val detailViewModel: TrainDetailViewModel = hiltViewModel()

    val sharer = rememberTrainTicketSharer()

    val listState by listViewModel.uiState.collectAsStateWithLifecycle()
    val formState by formViewModel.uiState.collectAsStateWithLifecycle()
    val detailState by detailViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialTicketId, initialAction) {
        if (initialTicketId == null) return@LaunchedEffect
        when (initialAction) {
            TrainsLandingAction.OPEN_DETAIL -> {
                screen = TrainsScreen.List
                detailTicketId = initialTicketId
            }
            TrainsLandingAction.DUPLICATE_PNR -> {
                screen = TrainsScreen.List
                showDuplicateNotice(snackbarHostState, context) { detailTicketId = initialTicketId }
            }
            TrainsLandingAction.OPEN_PNR_CHECK -> {
                // The just-saved ticket reaches the list through Room; wait for its
                // card (it carries the PNR) rather than threading the PNR through the
                // shell. If it never shows up, fall back to the detail sheet.
                val card =
                    withTimeoutOrNull(PNR_CHECK_LOOKUP_TIMEOUT_MILLIS) {
                        listViewModel.uiState
                            .map { state -> state.cards.firstOrNull { it.ticket.id == initialTicketId } }
                            .filterNotNull()
                            .first()
                    }
                if (card != null) {
                    detailTicketId = null
                    screen = TrainsScreen.PnrCheck(ticketId = card.ticket.id, pnr = card.ticket.pnr)
                } else {
                    screen = TrainsScreen.List
                    detailTicketId = initialTicketId
                }
            }
        }
    }

    LaunchedEffect(detailTicketId) { detailViewModel.setTicketId(detailTicketId) }

    LaunchedEffect(formViewModel) {
        formViewModel.events.collect { event ->
            when (event) {
                is TrainFormEvent.Saved -> {
                    if (event.openPnrCheck) {
                        // PNR-only quick add: go straight to the status check so the
                        // first fetch backfills the ticket (user request, ADR-023).
                        screen = TrainsScreen.PnrCheck(ticketId = event.ticketId, pnr = event.pnr)
                    } else {
                        screen = TrainsScreen.List
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.trains_form_saved))
                        }
                    }
                }
                is TrainFormEvent.PrefillEmpty ->
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.trains_import_failed))
                    }
                is TrainFormEvent.DuplicatePnr -> {
                    // Nothing was written (ADR-024): back to the list, which already
                    // shows the existing ticket, with the notice + a "View" action.
                    screen = TrainsScreen.List
                    scope.launch {
                        showDuplicateNotice(snackbarHostState, context) { detailTicketId = event.existingTicketId }
                    }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = screen) {
            is TrainsScreen.List ->
                TrainListScreen(
                    state = listState,
                    onFilterChange = listViewModel::setFilter,
                    onTicketClick = { ticket -> detailTicketId = ticket.id },
                    onCheckStatus = { ticket ->
                        screen = TrainsScreen.PnrCheck(ticketId = ticket.id, pnr = ticket.pnr)
                    },
                    onViewRoute = { card ->
                        // Offline route page when a route is stored; the WebView
                        // fetch flow directly otherwise (ADR-019).
                        screen =
                            if (card.hasRoute) {
                                TrainsScreen.RouteView(
                                    ticketId = card.ticket.id,
                                    trainNumber = card.ticket.trainNumber,
                                )
                            } else {
                                TrainsScreen.RouteFetch(
                                    ticketId = card.ticket.id,
                                    trainNumber = card.ticket.trainNumber,
                                )
                            }
                    },
                    onSeatMap = { card ->
                        screen = TrainsScreen.SeatMap(ticketId = card.ticket.id, trainNumber = card.ticket.trainNumber)
                    },
                    onShare = { card ->
                        // Image + deep-link caption via the share sheet; text-only
                        // fallback is reported so the user knows the picture was skipped.
                        runCatching { sharer.share(card) }
                            .onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.trains_share_failed))
                                }
                            }
                    },
                    onAdd = { choice ->
                        // A lingering "Ticket saved" would sit over the form's Save/
                        // Cancel row and eat the tap; it has done its job by now.
                        snackbarHostState.currentSnackbarData?.dismiss()
                        when (choice) {
                            is AddChoice.Manual -> formViewModel.startBlank()
                            is AddChoice.FromText -> formViewModel.startFromText(choice.text)
                            is AddChoice.FromFile -> formViewModel.startFromUri(choice.uri)
                        }
                        screen =
                            TrainsScreen.Form(
                                entry =
                                    when (choice) {
                                        is AddChoice.Manual -> FormEntry.Blank
                                        is AddChoice.FromText -> FormEntry.FromText(choice.text)
                                        is AddChoice.FromFile -> FormEntry.FromUri(choice.uri)
                                    },
                            )
                    },
                )
            is TrainsScreen.Form ->
                TrainTicketFormScreen(
                    state = formState,
                    viewModel = formViewModel,
                    onCancel = { screen = TrainsScreen.List },
                )
            is TrainsScreen.PnrCheck ->
                PnrCheckScreen(
                    ticketId = current.ticketId,
                    pnr = current.pnr,
                    onApplied = { resultTrainNumber ->
                        val card = listState.cards.firstOrNull { it.ticket.id == current.ticketId }
                        val trainNumber = card?.ticket?.trainNumber?.ifBlank { resultTrainNumber } ?: resultTrainNumber
                        if (card?.hasRoute != true && trainNumber.isNotBlank()) {
                            // Chain the hands-free route fetch so departure times and
                            // coach positions land right after the first PNR check.
                            screen = TrainsScreen.RouteFetch(ticketId = current.ticketId, trainNumber = trainNumber)
                        } else {
                            screen = TrainsScreen.List
                        }
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.trains_pnr_check_applied),
                            )
                        }
                    },
                    onClose = { screen = TrainsScreen.List },
                )
            is TrainsScreen.RouteFetch ->
                RouteFetchScreen(
                    ticketId = current.ticketId,
                    trainNumber = current.trainNumber,
                    onApplied = { stationCount ->
                        // Land on the OFFLINE route page so the freshly fetched
                        // route is immediately visible from Room (ADR-019) — or
                        // back on the seat map when the fetch started there.
                        screen =
                            if (current.returnToSeatMap) {
                                TrainsScreen.SeatMap(
                                    ticketId = current.ticketId,
                                    trainNumber = current.trainNumber,
                                )
                            } else {
                                TrainsScreen.RouteView(
                                    ticketId = current.ticketId,
                                    trainNumber = current.trainNumber,
                                )
                            }
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.trains_route_fetch_applied, stationCount),
                            )
                        }
                    },
                    onClose = { screen = TrainsScreen.List },
                )
            is TrainsScreen.SeatMap ->
                SeatMapScreen(
                    ticketId = current.ticketId,
                    onFetch = {
                        screen =
                            TrainsScreen.RouteFetch(
                                ticketId = current.ticketId,
                                trainNumber = current.trainNumber,
                                returnToSeatMap = true,
                            )
                    },
                    onClose = { screen = TrainsScreen.List },
                )
            is TrainsScreen.RouteView ->
                TrainRouteScreen(
                    ticketId = current.ticketId,
                    onRefresh = {
                        screen =
                            TrainsScreen.RouteFetch(
                                ticketId = current.ticketId,
                                trainNumber = current.trainNumber,
                            )
                    },
                    onClose = { screen = TrainsScreen.List },
                )
        }

        if (detailTicketId != null && detailState.ticket != null && screen is TrainsScreen.List) {
            TrainDetailSheet(
                state = detailState,
                onDismiss = { detailTicketId = null },
                onCheckStatus = {
                    val ticket = detailState.ticket
                    if (ticket != null) {
                        detailTicketId = null
                        screen = TrainsScreen.PnrCheck(ticketId = ticket.id, pnr = ticket.pnr)
                    }
                },
                onViewRoute = {
                    val ticket = detailState.ticket
                    if (ticket != null && ticket.trainNumber.isNotBlank()) {
                        detailTicketId = null
                        // Same conditional as the card action (ADR-019): offline
                        // page when a route is stored, fetch flow otherwise.
                        screen =
                            if (detailState.routeStops.isNotEmpty()) {
                                TrainsScreen.RouteView(
                                    ticketId = ticket.id,
                                    trainNumber = ticket.trainNumber,
                                )
                            } else {
                                TrainsScreen.RouteFetch(
                                    ticketId = ticket.id,
                                    trainNumber = ticket.trainNumber,
                                )
                            }
                    }
                },
                onSeatMap = {
                    val ticket = detailState.ticket
                    if (ticket != null) {
                        detailTicketId = null
                        screen = TrainsScreen.SeatMap(ticketId = ticket.id, trainNumber = ticket.trainNumber)
                    }
                },
                onEdit = {
                    val id = detailTicketId
                    if (id != null) {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        detailTicketId = null
                        formViewModel.startEdit(id)
                        screen = TrainsScreen.Form(entry = FormEntry.Edit(id))
                    }
                },
                onArchiveToggle = {
                    val ticket = detailState.ticket
                    if (ticket != null) {
                        detailViewModel.setArchived(!ticket.archived)
                        detailTicketId = null
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    if (ticket.archived) {
                                        R.string.trains_detail_unarchived
                                    } else {
                                        R.string.trains_detail_archived
                                    },
                                ),
                            )
                        }
                    }
                },
                onDelete = {
                    detailViewModel.delete()
                    detailTicketId = null
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.trains_detail_deleted))
                    }
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    // Clear the list's FAB (Material: snackbars sit ABOVE the FAB) —
                    // otherwise the FAB wins taps meant for the snackbar action.
                    .padding(bottom = if (screen is TrainsScreen.List) SNACKBAR_FAB_CLEARANCE else 0.dp),
        )
    }
}

/** FAB (56dp) + its 16dp margin + a little breathing room. */
private val SNACKBAR_FAB_CLEARANCE = 80.dp
