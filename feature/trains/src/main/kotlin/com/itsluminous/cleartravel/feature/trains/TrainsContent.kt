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
