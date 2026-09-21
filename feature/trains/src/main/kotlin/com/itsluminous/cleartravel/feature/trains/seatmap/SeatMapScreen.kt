package com.itsluminous.cleartravel.feature.trains.seatmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.feature.trains.R

private val CELL_SHAPE = RoundedCornerShape(10.dp)
private val BAY_SHAPE = RoundedCornerShape(14.dp)
private val COACH_SHAPE = RoundedCornerShape(8.dp)
private val CELL_WIDTH = 64.dp
private val CELL_HEIGHT = 60.dp

/**
 * The seat-map screen (ADR-022), modelled on the reference train tracker: a
 * horizontal COACH-POSITION strip (engine glyph + coach boxes with their position
 * below, the ticket coach filled, the viewed coach outlined), an accuracy WARNING
 * banner, then the BAY-WISE berth grid of the viewed coach's class — left block ·
 * aisle · side berths per row, every cell showing berth number + type, the ticket's
 * own berths highlighted. Everything renders from Room; [onFetch] opens the
 * route-fetch WebView flow, which is the only thing that writes coaches.
 */
@Composable
internal fun SeatMapScreen(
    ticketId: String,
    onFetch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SeatMapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ticketId) { viewModel.setTicketId(ticketId) }
    val canFetch = state.ticket?.trainNumber?.isNotBlank() == true

    Column(modifier = modifier.fillMaxSize()) {
        SeatMapTopBar(state = state, onClose = onClose, onFetch = onFetch.takeIf { canFetch })
        if (state.loading) return@Column
        if (!state.hasCoaches && state.layout == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    icon = Icons.Filled.Train,
                    title = stringResource(R.string.trains_seatmap_no_coaches_title),
                    message = stringResource(R.string.trains_seatmap_no_coaches_message),
                )
                if (canFetch) {
                    Button(onClick = onFetch, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.trains_seatmap_fetch))
                    }
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "passengers") { PassengerChips(seats = state.passengerSeats) }
            item(key = "strip") {
                if (state.hasCoaches) {
                    CoachStrip(items = state.strip, onSelect = viewModel::selectCoach)
                } else {
                    NoCoachesCard(onFetch = onFetch.takeIf { canFetch })
                }
            }
            item(key = "warning") { WarningBanner() }
            val layout = state.layout
            if (layout == null) {
                item(key = "no-layout") {
                    EmptyState(
                        icon = Icons.Filled.AirlineSeatReclineNormal,
                        title = stringResource(R.string.trains_seatmap_no_layout_title),
                        message = stringResource(R.string.trains_seatmap_no_layout_message),
                        modifier = Modifier.height(260.dp),
                    )
                }
            } else {
                items(layout.bays, key = { "bay-${it.index}" }) { bay ->
                    BayBlock(bay = bay, kind = layout.kind, highlighted = state.highlightedBerths)
                }
            }
        }
    }
}

@Composable
private fun SeatMapTopBar(
    state: SeatMapUiState,
    onClose: () -> Unit,
    onFetch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val ticket = state.ticket
    val coach = state.selectedCoachCode
    val className = state.layout?.displayName
    val title =
        when {
            coach != null && className != null -> stringResource(R.string.trains_seatmap_title, coach, className)
            coach != null -> stringResource(R.string.trains_seatmap_title_class_only, coach)
            className != null -> stringResource(R.string.trains_seatmap_title_class_only, className)
            else -> stringResource(R.string.trains_seatmap_title_fallback)
        }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExplainableIcon(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            explanationRes = R.string.trains_seatmap_close,
            onClick = onClose,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (ticket != null && (ticket.trainNumber.isNotBlank() || ticket.trainName.isNotBlank())) {
                Text(
                    text =
                        when {
                            ticket.trainNumber.isNotBlank() && ticket.trainName.isNotBlank() ->
                                stringResource(R.string.trains_seatmap_subtitle, ticket.trainNumber, ticket.trainName)
                            else -> ticket.trainNumber.ifBlank { ticket.trainName }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onFetch != null) {
            ExplainableIcon(
                icon = Icons.Filled.Refresh,
                explanationRes = R.string.trains_seatmap_refresh,
                onClick = onFetch,
            )
        }
    }
}

@Composable
private fun PassengerChips(
    seats: List<PassengerSeat>,
    modifier: Modifier = Modifier,
) {
    if (seats.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(seats) { seat ->
            val label =
                when {
                    seat.berthNumber != null && seat.coach.isNotEmpty() ->
                        stringResource(R.string.trains_seatmap_passenger_chip, seat.coach, seat.berthNumber)
                    seat.berthNumber != null -> stringResource(R.string.trains_seatmap_passenger_chip_no_coach, seat.berthNumber)
                    seat.rawSeat.isNotEmpty() -> stringResource(R.string.trains_seatmap_passenger_chip_unallotted, seat.rawSeat)
                    else -> stringResource(R.string.trains_seatmap_passenger_chip_none)
                }
            SuggestionChip(onClick = {}, label = { Text(label) })
        }
    }
}

/**
 * Engine glyph + one box per coach, position number beneath (reference strip). The
 * strip scrolls once so the ticket coach is visible — rakes run 20+ coaches and the
 * booked one is rarely near the engine.
 */
@Composable
private fun CoachStrip(
    items: List<CoachStripItem>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val ticketIndex = items.indexOfFirst { it.isTicketCoach }
    LaunchedEffect(ticketIndex, items.size) {
        if (ticketIndex > 0) listState.animateScrollToItem((ticketIndex - 2).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        items(items, key = { "${it.code}-${it.position ?: 0}" }) { item ->
            if (item.isEngine) {
                EngineBox()
            } else {
                CoachBox(item = item, onClick = { onSelect(item.code) })
            }
        }
    }
}

@Composable
private fun EngineBox(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.trains_seatmap_engine),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier =
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 56.dp, height = 44.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, COACH_SHAPE),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Train,
                contentDescription = stringResource(R.string.trains_seatmap_engine),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun CoachBox(
    item: CoachStripItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fill =
        when {
            item.isTicketCoach -> colors.primary
            item.isSelected -> colors.secondaryContainer
            else -> colors.surfaceVariant
        }
    val onFill =
        when {
            item.isTicketCoach -> colors.onPrimary
            item.isSelected -> colors.onSecondaryContainer
            else -> colors.onSurfaceVariant
        }
    val border = if (item.isSelected && !item.isTicketCoach) BorderStroke(2.dp, colors.primary) else null
    val description = stringResource(R.string.trains_seatmap_coach_box, item.code, item.position ?: 0)
    Column(
        modifier =
            modifier
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = item.code,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (item.isTicketCoach) FontWeight.Bold else FontWeight.Normal,
            color = if (item.isTicketCoach) colors.primary else colors.onSurface,
        )
        Box(
            modifier =
                Modifier
                    .padding(top = 4.dp)
                    .size(width = 56.dp, height = 44.dp)
                    .background(fill, COACH_SHAPE)
                    .then(if (border != null) Modifier.border(border, COACH_SHAPE) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            // Two "windows" hint at a coach; purely decorative.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(2) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 14.dp, height = 10.dp)
                                .background(onFill.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Box(
            modifier =
                Modifier
                    .padding(top = 6.dp)
                    .size(22.dp)
                    .background(if (item.isTicketCoach) colors.primary else Color.Transparent, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (item.position ?: 0).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (item.isTicketCoach) colors.onPrimary else colors.onSurface,
            )
        }
    }
}

@Composable
private fun NoCoachesCard(
    onFetch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ClearTravelCard(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.trains_seatmap_no_coaches_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.trains_seatmap_no_coaches_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (onFetch != null) {
            Button(onClick = onFetch, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.trains_seatmap_fetch))
            }
        }
    }
}

@Composable
private fun WarningBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.trains_seatmap_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** One bay: an outlined block with its rows — left cells · aisle · side cells. */
@Composable
private fun BayBlock(
    bay: Bay,
    kind: SeatKind,
    highlighted: Set<Int>,
    modifier: Modifier = Modifier,
) {
    val label =
        if (kind == SeatKind.BERTH) {
            stringResource(R.string.trains_seatmap_bay, bay.index)
        } else {
            stringResource(R.string.trains_seatmap_row, bay.index)
        }
    Column(
        modifier =
            modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, BAY_SHAPE)
                .padding(10.dp)
                .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        bay.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.left.forEach { berth -> BerthCell(berth = berth, highlighted = berth.number in highlighted) }
                }
                Spacer(modifier = Modifier.weight(1f).width(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.right.forEach { berth -> BerthCell(berth = berth, highlighted = berth.number in highlighted) }
                }
            }
        }
    }
}

@Composable
private fun BerthCell(
    berth: Berth,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val typeLabel = stringResource(berthTypeLabel(berth.type))
    val description =
        if (highlighted) {
            stringResource(R.string.trains_seatmap_cell_yours, berth.number, typeLabel)
        } else {
            stringResource(R.string.trains_seatmap_cell, berth.number, typeLabel)
        }
    Column(
        modifier =
            modifier
                .size(width = CELL_WIDTH, height = CELL_HEIGHT)
                .background(if (highlighted) colors.tertiary else colors.surfaceContainerHigh, CELL_SHAPE)
                .border(
                    width = if (highlighted) 2.dp else 1.dp,
                    color = if (highlighted) colors.tertiary else colors.outlineVariant,
                    shape = CELL_SHAPE,
                ).semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = berth.number.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) colors.onTertiary else colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) colors.onTertiary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Berth type → its string resource (data enum, resource label — hard rule 1). */
internal fun berthTypeLabel(type: BerthType): Int =
    when (type) {
        BerthType.LOWER -> R.string.trains_seatmap_type_lower
        BerthType.MIDDLE -> R.string.trains_seatmap_type_middle
        BerthType.UPPER -> R.string.trains_seatmap_type_upper
        BerthType.SIDE_LOWER -> R.string.trains_seatmap_type_side_lower
        BerthType.SIDE_UPPER -> R.string.trains_seatmap_type_side_upper
        BerthType.WINDOW -> R.string.trains_seatmap_type_window
        BerthType.AISLE -> R.string.trains_seatmap_type_aisle
    }
