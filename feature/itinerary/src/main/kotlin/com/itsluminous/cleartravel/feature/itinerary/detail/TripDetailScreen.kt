package com.itsluminous.cleartravel.feature.itinerary.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelFab
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.formatMedium
import com.itsluminous.cleartravel.feature.itinerary.icon
import com.itsluminous.cleartravel.feature.itinerary.labelRes
import com.itsluminous.cleartravel.feature.itinerary.logic.ItineraryDay
import com.itsluminous.cleartravel.feature.itinerary.logic.dayColorArgb

/** Timeline vs map — the two toggles of the same trip screen. */
private const val VIEW_TIMELINE = 0
private const val VIEW_MAP = 1

/** One trip: day-grouped timeline and map view behind a segmented toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TripDetailScreen(
    onBack: () -> Unit,
    onAddItem: (tripId: String, dayIndex: Int) -> Unit,
    onEditItem: (tripId: String, itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripDetailViewModel = hiltViewModel(),
    /** ADR-028: a linked journey was tapped in the item sheet — the shell opens it in Journeys. */
    onOpenJourney: (JourneyType, String) -> Unit = { _, _ -> },
) {
    val trip by viewModel.trip.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val mapContent by viewModel.mapContent.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var selectedView by rememberSaveable { mutableStateOf(VIEW_TIMELINE) }
    var sheetItem by remember { mutableStateOf<ItineraryItem?>(null) }
    var deleteTarget by remember { mutableStateOf<ItineraryItem?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(context.getString(current.labelRes()))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val current = trip
                    Text(
                        text =
                            if (current == null) {
                                stringResource(R.string.itinerary_trip_missing_title)
                            } else {
                                listOf(current.coverEmoji, current.name)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.itinerary_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            trip?.let { current ->
                ClearTravelFab(
                    onClick = {
                        onAddItem(current.id, days.lastOrNull()?.dayIndex ?: 0)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.itinerary_add_item),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (trip == null) {
                EmptyState(
                    icon = Icons.Filled.EventNote,
                    title = stringResource(R.string.itinerary_trip_missing_title),
                    message = stringResource(R.string.itinerary_trip_missing_message),
                )
                return@Column
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                SegmentedButton(
                    selected = selectedView == VIEW_TIMELINE,
                    onClick = { selectedView = VIEW_TIMELINE },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.itinerary_view_timeline)) }
                SegmentedButton(
                    selected = selectedView == VIEW_MAP,
                    onClick = { selectedView = VIEW_MAP },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.itinerary_view_map)) }
            }
            when (selectedView) {
                VIEW_TIMELINE ->
                    TimelineView(
                        days = days,
                        onItemClick = { sheetItem = it },
                        onMove = viewModel::moveItem,
                    )
                VIEW_MAP ->
                    TripMapView(
                        content = mapContent,
                        onMarkerClick = { sheetItem = it },
                    )
            }
        }
    }

    sheetItem?.let { item ->
        ItineraryItemSheet(
            item = item,
            onDismiss = { sheetItem = null },
            onEdit = {
                sheetItem = null
                onEditItem(item.tripId, item.id)
            },
            onDelete = {
                sheetItem = null
                deleteTarget = item
            },
            onOpenLinkedJourney = { type, id ->
                sheetItem = null
                onOpenJourney(type, id)
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.itinerary_delete_item_title)) },
            text = { Text(stringResource(R.string.itinerary_delete_item_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(item.id)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.itinerary_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.itinerary_cancel))
                }
            },
        )
    }
}

@Composable
private fun TimelineView(
    days: List<ItineraryDay>,
    onItemClick: (ItineraryItem) -> Unit,
    onMove: (itemId: String, delta: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (days.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.EventNote,
            title = stringResource(R.string.itinerary_empty_items_title),
            message = stringResource(R.string.itinerary_empty_items_message),
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        days.forEach { day ->
            item(key = "day-${day.dayIndex}") {
                DayHeader(day = day)
            }
            day.items.forEachIndexed { index, dayItem ->
                item(key = dayItem.id) {
                    ItineraryItemCard(
                        item = dayItem,
                        canMoveUp = index > 0,
                        canMoveDown = index < day.items.lastIndex,
                        onClick = { onItemClick(dayItem) },
                        onMove = { delta -> onMove(dayItem.id, delta) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeader(
    day: ItineraryDay,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(12.dp)
                    .background(Color(dayColorArgb(day.dayIndex)), CircleShape),
        )
        Text(
            text = stringResource(R.string.itinerary_day_label, day.dayIndex + 1),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
        day.date?.let { date ->
            Text(
                text = date.formatMedium(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ItineraryItemCard(
    item: ItineraryItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMove: (delta: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isCommute = item.type == ItineraryItemType.COMMUTE
            ExplainableIcon(
                icon = if (isCommute) item.commuteMode.icon() else item.category.icon(),
                explanationRes = if (isCommute) item.commuteMode.labelRes() else item.category.labelRes(),
                targetSize = 40.dp,
            )
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text =
                        if (isCommute) {
                            stringResource(R.string.itinerary_commute_route, item.fromName, item.toName)
                        } else {
                            item.name
                        },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.plannedTime.isNotBlank()) {
                    Text(
                        text = item.plannedTime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isCommute && item.note.isNotBlank()) {
                    Text(
                        text = item.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canMoveUp) {
                ExplainableIcon(
                    icon = Icons.Filled.KeyboardArrowUp,
                    explanationRes = R.string.itinerary_move_up,
                    targetSize = 36.dp,
                    onClick = { onMove(-1) },
                )
            }
            if (canMoveDown) {
                ExplainableIcon(
                    icon = Icons.Filled.KeyboardArrowDown,
                    explanationRes = R.string.itinerary_move_down,
                    targetSize = 36.dp,
                    onClick = { onMove(1) },
                )
            }
        }
    }
}
