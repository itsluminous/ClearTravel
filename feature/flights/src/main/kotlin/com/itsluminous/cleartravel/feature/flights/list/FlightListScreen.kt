package com.itsluminous.cleartravel.feature.flights.list

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.designsystem.component.FullWidthFilterChip
import com.itsluminous.cleartravel.core.designsystem.component.FullWidthFilterRow
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.R
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInGateDecision
import com.itsluminous.cleartravel.feature.flights.detail.FlightDetailSheet
import com.itsluminous.cleartravel.feature.flights.detail.FlightDocumentType
import com.itsluminous.cleartravel.feature.flights.detail.FlightDocumentsViewModel
import com.itsluminous.cleartravel.feature.flights.detail.FlightTripLinksViewModel
import com.itsluminous.cleartravel.feature.flights.detail.buildFlightDocuments
import com.itsluminous.cleartravel.feature.flights.share.FlightShareOutcome
import com.itsluminous.cleartravel.feature.flights.share.rememberFlightSharer
import com.itsluminous.cleartravel.feature.flights.status.CheckOutcome
import com.itsluminous.cleartravel.feature.flights.status.CheckOutcomeKind
import kotlinx.coroutines.launch

/**
 * A refused duplicate save (ADR-025) to explain over the list: the existing journey's
 * card is already on screen; the snackbar's "View" opens its detail sheet. [nonce]
 * makes two consecutive refusals of the same journey distinct so the notice re-fires.
 */
data class DuplicateFlightNotice(
    val existingFlightId: String,
    val nonce: Long = System.nanoTime(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightListScreen(
    onAddManual: () -> Unit,
    onImportPass: (uriString: String) -> Unit,
    onEdit: (flightId: String) -> Unit,
    onCheckStatus: (flightId: String) -> Unit,
    onViewPass: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightListViewModel = hiltViewModel(),
    documentsViewModel: FlightDocumentsViewModel = hiltViewModel(),
    /** Third add path (ADR-017): picked booking-confirmation file to prefill from. */
    onImportBooking: (uriString: String) -> Unit = {},
    /** Opens an attached booking confirmation in the document viewer. */
    onViewBooking: (path: String) -> Unit = {},
    /** Deep-link hook: opens this flight's detail sheet on first composition. */
    initialDetailFlightId: String? = null,
    /**
     * Transient outcome of the last completed status check (D2): reopens the
     * flight's detail sheet — which renders the outcome line — plus a snackbar.
     */
    lastCheckOutcome: CheckOutcome? = null,
    /** Duplicate-save notice (ADR-025) with a "View" action for the existing journey. */
    duplicateNotice: DuplicateFlightNotice? = null,
    /** ADR-028: opens the add-options sheet on arrival (one open per distinct nonce). */
    openAddSheetNonce: Long? = null,
    /** The add options were left without picking a path (sheet dismissed, no file chosen). */
    onAddAbandoned: () -> Unit = {},
    /** "Part of" rows (ADR-028) — the shell opens the tapped trip in the Trips tab. */
    onOpenTrip: (tripId: String) -> Unit = {},
    tripLinksViewModel: FlightTripLinksViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showAddOptions by remember { mutableStateOf(false) }
    var detailFlightId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(openAddSheetNonce) {
        if (openAddSheetNonce != null) showAddOptions = true
    }

    LaunchedEffect(initialDetailFlightId) {
        if (initialDetailFlightId != null) detailFlightId = initialDetailFlightId
    }

    val checkUpdatedSnackbar = stringResource(R.string.flights_check_snackbar_updated)
    val checkNoChangesSnackbar = stringResource(R.string.flights_check_snackbar_no_changes)
    val checkFailedSnackbar = stringResource(R.string.flights_check_snackbar_failed)
    LaunchedEffect(lastCheckOutcome) {
        if (lastCheckOutcome != null) {
            detailFlightId = lastCheckOutcome.flightId
            snackbarHostState.showSnackbar(
                when (lastCheckOutcome.kind) {
                    CheckOutcomeKind.UPDATED -> checkUpdatedSnackbar
                    CheckOutcomeKind.NO_CHANGES -> checkNoChangesSnackbar
                    CheckOutcomeKind.FAILED -> checkFailedSnackbar
                },
            )
        }
    }

    // The sheet is NOT opened automatically: a modal sheet would cover the snackbar
    // and hide the explanation. Scaffold places the snackbar above the FAB.
    val duplicateMessage = stringResource(R.string.flights_form_duplicate)
    val duplicateViewLabel = stringResource(R.string.flights_form_duplicate_view)
    LaunchedEffect(duplicateNotice) {
        if (duplicateNotice != null) {
            val result =
                snackbarHostState.showSnackbar(
                    message = duplicateMessage,
                    actionLabel = duplicateViewLabel,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) detailFlightId = duplicateNotice.existingFlightId
        }
    }

    val passPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportPass(uri.toString()) else onAddAbandoned()
        }
    val bookingImportPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onImportBooking(uri.toString()) else onAddAbandoned()
        }

    // Attach-to-existing-flight flow (ADR-017): the picker result lands after the
    // detail sheet may have moved, so the target flight id is captured up front.
    val bookingAttachedSnackbar = stringResource(R.string.flights_booking_attached_snackbar)
    val bookingAttachFailedSnackbar = stringResource(R.string.flights_booking_attach_failed_snackbar)
    var attachTargetFlightId by remember { mutableStateOf<String?>(null) }
    val bookingAttachPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val target = attachTargetFlightId
            attachTargetFlightId = null
            if (uri != null && target != null) {
                documentsViewModel.attachBookingConfirmation(target, uri.toString()) { attached ->
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (attached) bookingAttachedSnackbar else bookingAttachFailedSnackbar,
                        )
                    }
                }
            }
        }

    val archivedSnackbar = stringResource(R.string.flights_archived_snackbar)
    val unarchivedSnackbar = stringResource(R.string.flights_unarchived_snackbar)
    val deletedSnackbar = stringResource(R.string.flights_deleted_snackbar)

    // ADR-039 part A: card quick actions. The check-in tap is gated on the airline's
    // window; every refusal is explained in a snackbar (never a toast).
    val context = LocalContext.current
    val sharer = rememberFlightSharer()
    val shareTextOnlySnackbar = stringResource(R.string.flights_share_text_only)
    val shareFailedSnackbar = stringResource(R.string.flights_share_failed)
    val checkInClosedSnackbar = stringResource(R.string.flights_checkin_closed)
    val checkInDepartedSnackbar = stringResource(R.string.flights_checkin_departed)
    val checkInOpensTemplate = stringResource(R.string.flights_checkin_opens_at)

    fun onWebCheckIn(flight: FlightJourney) {
        when (val decision = viewModel.checkInGate(flight)) {
            is CheckInGateDecision.Open -> context.startActivity(Intent(Intent.ACTION_VIEW, decision.url.toUri()))
            is CheckInGateDecision.NotYetOpen ->
                scope.launch {
                    snackbarHostState.showSnackbar(
                        String.format(checkInOpensTemplate, formatTimestamp(decision.opensAt).orEmpty()),
                    )
                }
            is CheckInGateDecision.Closed -> scope.launch { snackbarHostState.showSnackbar(checkInClosedSnackbar) }
            CheckInGateDecision.Departed -> scope.launch { snackbarHostState.showSnackbar(checkInDepartedSnackbar) }
        }
    }

    fun onShare(flight: FlightJourney) {
        val outcome = runCatching { sharer.share(flight) }.getOrDefault(FlightShareOutcome.FAILED)
        when (outcome) {
            FlightShareOutcome.SHARED -> Unit
            FlightShareOutcome.SHARED_TEXT_ONLY -> scope.launch { snackbarHostState.showSnackbar(shareTextOnlySnackbar) }
            FlightShareOutcome.FAILED -> scope.launch { snackbarHostState.showSnackbar(shareFailedSnackbar) }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ClearTravelFab(onClick = { showAddOptions = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.flights_add))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FullWidthFilterRow {
                FullWidthFilterChip(
                    selected = uiState.filter == FlightListFilter.ACTIVE,
                    onClick = { viewModel.setFilter(FlightListFilter.ACTIVE) },
                    label = stringResource(R.string.flights_filter_active),
                )
                FullWidthFilterChip(
                    selected = uiState.filter == FlightListFilter.ARCHIVED,
                    onClick = { viewModel.setFilter(FlightListFilter.ARCHIVED) },
                    label = stringResource(R.string.flights_filter_archived),
                )
            }

            if (uiState.flights.isEmpty()) {
                val archived = uiState.filter == FlightListFilter.ARCHIVED
                EmptyState(
                    icon = Icons.Filled.Flight,
                    title =
                        stringResource(
                            if (archived) R.string.flights_empty_archived_title else R.string.flights_empty_title,
                        ),
                    message =
                        stringResource(
                            if (archived) R.string.flights_empty_archived_message else R.string.flights_empty_message,
                        ),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.flights, key = { it.id }) { flight ->
                        FlightCard(
                            flight = flight,
                            onClick = { detailFlightId = flight.id },
                            onCheckStatus = { onCheckStatus(flight.id) },
                            onWebCheckIn = { onWebCheckIn(flight) },
                            onShare = { onShare(flight) },
                        )
                    }
                }
            }
        }
    }

    if (showAddOptions) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddOptions = false
                onAddAbandoned()
            },
        ) {
            Text(
                text = stringResource(R.string.flights_add_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.flights_add_manual)) },
                supportingContent = { Text(stringResource(R.string.flights_add_manual_hint)) },
                leadingContent = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        showAddOptions = false
                        onAddManual()
                    },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.flights_add_import)) },
                supportingContent = { Text(stringResource(R.string.flights_add_import_hint)) },
                leadingContent = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        showAddOptions = false
                        passPicker.launch(arrayOf("image/*", "application/pdf"))
                    },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.flights_add_booking)) },
                supportingContent = { Text(stringResource(R.string.flights_add_booking_hint)) },
                leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                modifier =
                    Modifier.clickable {
                        showAddOptions = false
                        bookingImportPicker.launch(arrayOf("image/*", "application/pdf"))
                    },
            )
            Spacer(modifier = Modifier.padding(bottom = 24.dp))
        }
    }

    val detailFlight = uiState.flights.firstOrNull { it.id == detailFlightId }
    if (detailFlight != null) {
        val attachments by remember(detailFlight.id) {
            documentsViewModel.observeAttachments(detailFlight.id)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        val linkedTrips by remember(detailFlight.id) {
            tripLinksViewModel.observeLinkedTrips(detailFlight.id)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        FlightDetailSheet(
            flight = detailFlight,
            lastCheckOutcome = lastCheckOutcome?.takeIf { it.flightId == detailFlight.id },
            documents = buildFlightDocuments(detailFlight, attachments),
            linkedTrips = linkedTrips,
            onOpenTrip = { tripId ->
                detailFlightId = null
                onOpenTrip(tripId)
            },
            onOpenDocument = { document ->
                detailFlightId = null
                when (document.type) {
                    FlightDocumentType.BOARDING_PASS -> onViewPass(document.path)
                    FlightDocumentType.BOOKING_CONFIRMATION -> onViewBooking(document.path)
                }
            },
            onAttachBooking = {
                attachTargetFlightId = detailFlight.id
                bookingAttachPicker.launch(arrayOf("image/*", "application/pdf"))
            },
            onDismiss = { detailFlightId = null },
            onCheckStatus = {
                detailFlightId = null
                onCheckStatus(detailFlight.id)
            },
            onEdit = {
                detailFlightId = null
                onEdit(detailFlight.id)
            },
            onViewPass = { path ->
                detailFlightId = null
                onViewPass(path)
            },
            onArchiveToggle = {
                val archiving = !detailFlight.archived
                viewModel.setArchived(detailFlight.id, archiving)
                detailFlightId = null
                scope.launch {
                    snackbarHostState.showSnackbar(if (archiving) archivedSnackbar else unarchivedSnackbar)
                }
            },
            onDelete = {
                viewModel.delete(detailFlight.id)
                detailFlightId = null
                scope.launch { snackbarHostState.showSnackbar(deletedSnackbar) }
            },
        )
    }
}

/**
 * A flight card: the informational body ([FlightCardBody]) plus — like the train
 * card (ADR-020) — a vertical quick-action column on the right: check status, web
 * check-in (window-gated, ADR-039 part A) and share (image + add-data link).
 */
@Composable
private fun FlightCard(
    flight: FlightJourney,
    onClick: () -> Unit,
    onCheckStatus: () -> Unit,
    onWebCheckIn: () -> Unit,
    onShare: () -> Unit,
) {
    ClearTravelCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            FlightCardBody(flight = flight, modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier.padding(start = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ExplainableIcon(
                    icon = Icons.Filled.Refresh,
                    explanationRes = R.string.flights_card_check_status,
                    tint = MaterialTheme.colorScheme.primary,
                    targetSize = 40.dp,
                    onClick = onCheckStatus,
                )
                ExplainableIcon(
                    icon = Icons.Filled.HowToReg,
                    explanationRes = R.string.flights_card_web_checkin,
                    tint = MaterialTheme.colorScheme.primary,
                    targetSize = 40.dp,
                    onClick = onWebCheckIn,
                )
                ExplainableIcon(
                    icon = Icons.Filled.Share,
                    explanationRes = R.string.flights_card_share,
                    tint = MaterialTheme.colorScheme.primary,
                    targetSize = 40.dp,
                    onClick = onShare,
                )
            }
        }
    }
}
