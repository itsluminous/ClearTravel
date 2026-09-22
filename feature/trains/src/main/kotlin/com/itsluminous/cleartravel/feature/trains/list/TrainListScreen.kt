package com.itsluminous.cleartravel.feature.trains.list

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.designsystem.component.FullWidthFilterChip
import com.itsluminous.cleartravel.core.designsystem.component.FullWidthFilterRow
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.feature.trains.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** How the user wants to start a new ticket (FAB → add-options sheet). */
internal sealed interface AddChoice {
    data object Manual : AddChoice

    data class FromText(
        val text: String,
    ) : AddChoice

    data class FromFile(
        val uri: Uri,
    ) : AddChoice
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrainListScreen(
    state: TrainListUiState,
    onFilterChange: (TrainListFilter) -> Unit,
    onTicketClick: (TrainTicket) -> Unit,
    onCheckStatus: (TrainTicket) -> Unit,
    onViewRoute: (TrainTicketCard) -> Unit,
    onSeatMap: (TrainTicketCard) -> Unit,
    onShare: (TrainTicketCard) -> Unit,
    onAdd: (AddChoice) -> Unit,
    modifier: Modifier = Modifier,
    /** ADR-028: opens the add-options sheet on arrival (one open per distinct nonce). */
    openAddSheetNonce: Long? = null,
    /** The add options were left without picking a path (sheet/paste dismissed, no file chosen). */
    onAddAbandoned: () -> Unit = {},
) {
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showPasteDialog by rememberSaveable { mutableStateOf(false) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAdd(AddChoice.FromFile(uri)) else onAddAbandoned()
        }

    LaunchedEffect(openAddSheetNonce) {
        if (openAddSheetNonce != null) showAddSheet = true
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FullWidthFilterRow {
                FullWidthFilterChip(
                    selected = state.filter == TrainListFilter.ACTIVE,
                    onClick = { onFilterChange(TrainListFilter.ACTIVE) },
                    label = stringResource(R.string.trains_filter_active),
                )
                FullWidthFilterChip(
                    selected = state.filter == TrainListFilter.ARCHIVED,
                    onClick = { onFilterChange(TrainListFilter.ARCHIVED) },
                    label = stringResource(R.string.trains_filter_archived),
                )
            }
            if (state.isEmpty) {
                val archived = state.filter == TrainListFilter.ARCHIVED
                EmptyState(
                    icon = Icons.Filled.Train,
                    title =
                        stringResource(
                            if (archived) R.string.trains_empty_archive_title else R.string.trains_empty_title,
                        ),
                    message =
                        stringResource(
                            if (archived) R.string.trains_empty_archive_message else R.string.trains_empty_message,
                        ),
                )
            } else {
                // One clock read per list emission keeps every card's "Updated X ago"
                // consistent within a frame.
                val now = remember(state.cards) { Instant.now() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.cards, key = { it.ticket.id }) { card ->
                        TrainTicketCardItem(
                            card = card,
                            now = now,
                            onClick = { onTicketClick(card.ticket) },
                            onCheckStatus = { onCheckStatus(card.ticket) },
                            onViewRoute = { onViewRoute(card) },
                            onSeatMap = { onSeatMap(card) },
                            onShare = { onShare(card) },
                        )
                    }
                }
            }
        }
        ClearTravelFab(
            onClick = { showAddSheet = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.trains_add_ticket),
            )
        }
    }

    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddSheet = false
                onAddAbandoned()
            },
        ) {
            AddOptionRow(
                icon = Icons.Filled.Edit,
                titleRes = R.string.trains_add_manual,
                hintRes = R.string.trains_add_manual_hint,
                onClick = {
                    showAddSheet = false
                    onAdd(AddChoice.Manual)
                },
            )
            AddOptionRow(
                icon = Icons.Filled.ContentPaste,
                titleRes = R.string.trains_add_paste,
                hintRes = R.string.trains_add_paste_hint,
                onClick = {
                    showAddSheet = false
                    showPasteDialog = true
                },
            )
            AddOptionRow(
                icon = Icons.Filled.UploadFile,
                titleRes = R.string.trains_add_import,
                hintRes = R.string.trains_add_import_hint,
                onClick = {
                    showAddSheet = false
                    importLauncher.launch(arrayOf("image/*", "application/pdf"))
                },
            )
        }
    }

    if (showPasteDialog) {
        PasteTextDialog(
            onDismiss = {
                showPasteDialog = false
                onAddAbandoned()
            },
            onConfirm = { text ->
                showPasteDialog = false
                onAdd(AddChoice.FromText(text))
            },
        )
    }
}

@Composable
private fun AddOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleRes: Int,
    hintRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(stringResource(titleRes)) },
        supportingContent = { Text(stringResource(hintRes)) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PasteTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.trains_paste_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.trains_paste_dialog_label)) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.trains_paste_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.trains_form_cancel))
            }
        },
    )
}

@Composable
private fun TrainTicketCardItem(
    card: TrainTicketCard,
    now: Instant,
    onClick: () -> Unit,
    onCheckStatus: () -> Unit,
    onViewRoute: () -> Unit,
    onSeatMap: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ticket = card.ticket
    ElevatedCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // Reference layout: header band (from → date/time → to) across the top…
        TicketHeaderBand(ticket = ticket, departureTime = card.departureTime)
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // …a slim accent stripe on the left…
            Box(
                modifier =
                    Modifier
                        .width(6.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.tertiary),
            )
            // …body lines + status pills…
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            ) {
                TicketBodyLines(
                    ticket = ticket,
                    now = now,
                    trailingTitleContent =
                        if (ticket.archived) {
                            {
                                Text(
                                    text = stringResource(R.string.trains_card_archived_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            null
                        },
                )
                StatusPillRow(passengers = card.passengers, modifier = Modifier.padding(top = 10.dp))
            }
            // …and a vertical action column on the right (all ExplainableIcon).
            Column(
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ExplainableIcon(
                    icon = Icons.Filled.Refresh,
                    explanationRes = R.string.trains_card_check_status,
                    tint = MaterialTheme.colorScheme.primary,
                    targetSize = 40.dp,
                    onClick = onCheckStatus,
                )
                ExplainableIcon(
                    icon = Icons.Filled.AirlineSeatReclineNormal,
                    explanationRes = R.string.trains_card_seat_map,
                    tint = MaterialTheme.colorScheme.primary,
                    targetSize = 40.dp,
                    onClick = onSeatMap,
                )
                if (ticket.trainNumber.isNotBlank()) {
                    ExplainableIcon(
                        icon = Icons.Filled.Place,
                        explanationRes = R.string.trains_card_view_route,
                        tint = MaterialTheme.colorScheme.primary,
                        targetSize = 40.dp,
                        onClick = onViewRoute,
                    )
                }
                ExplainableIcon(
                    icon = Icons.Filled.Share,
                    explanationRes = R.string.trains_card_share,
                    tint = MaterialTheme.colorScheme.primary,
                    targetSize = 40.dp,
                    onClick = onShare,
                )
            }
        }
    }
}

@Composable
internal fun lastFetchedText(ticket: TrainTicket): String {
    val fetched = ticket.lastFetchedAt
    return if (fetched == null) {
        stringResource(R.string.trains_card_never_fetched)
    } else {
        stringResource(
            R.string.trains_card_last_fetched,
            TIMESTAMP_FORMAT.format(fetched.atZone(ZoneId.systemDefault())),
        )
    }
}

internal fun formatDate(date: LocalDate): String = DATE_FORMAT.format(date)
