package com.itsluminous.cleartravel.feature.trains

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.itsluminous.cleartravel.feature.trains.share.rememberTrainTicketSharer
import kotlinx.coroutines.launch

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
 * [initialTicketId] is the notification deep-link hook (integration contract): when
 * non-null the list opens with that ticket's detail sheet expanded. Defaulted so
 * existing call sites are untouched.
 */
@Composable
fun TrainsContent(
    modifier: Modifier = Modifier,
    initialTicketId: String? = null,
) {
    var screen by remember { mutableStateOf<TrainsScreen>(TrainsScreen.List) }
    var detailTicketId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialTicketId) {
        if (initialTicketId != null) {
            screen = TrainsScreen.List
            detailTicketId = initialTicketId
        }
    }
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

    LaunchedEffect(detailTicketId) { detailViewModel.setTicketId(detailTicketId) }

    LaunchedEffect(formViewModel) {
        formViewModel.events.collect { event ->
            when (event) {
                is TrainFormEvent.Saved -> {
                    screen = TrainsScreen.List
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.trains_form_saved))
                    }
                }
                is TrainFormEvent.PrefillEmpty ->
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.trains_import_failed))
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
                    onApplied = {
                        screen = TrainsScreen.List
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
                        // route is immediately visible from Room (ADR-019).
                        screen =
                            TrainsScreen.RouteView(
                                ticketId = current.ticketId,
                                trainNumber = current.trainNumber,
                            )
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.trains_route_fetch_applied, stationCount),
                            )
                        }
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
                onEdit = {
                    val id = detailTicketId
                    if (id != null) {
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
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
