package com.itsluminous.cleartravel.feature.trains.route

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.feature.trains.R
import com.itsluminous.cleartravel.feature.trains.haltMinutes
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val FETCHED_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private const val MINUTES_PER_HOUR = 60L

/**
 * The OFFLINE route page (ADR-019): renders the stored route from Room only —
 * station list with arrival/departure, day section headers for multi-day journeys,
 * platform and derived halt per stop, a journey-duration + last-fetched header, and
 * a refresh action that opens the WebView fetch flow ([RouteFetchScreen]) ON DEMAND.
 * With no stored route it shows an [EmptyState] prompting a first fetch.
 */
@Composable
internal fun TrainRouteScreen(
    ticketId: String,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrainRouteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(ticketId) { viewModel.setTicketId(ticketId) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExplainableIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                explanationRes = R.string.trains_route_close,
                onClick = onClose,
            )
            Text(
                text = stringResource(R.string.trains_route_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            ExplainableIcon(
                icon = Icons.Filled.Refresh,
                explanationRes = R.string.trains_route_refresh,
                onClick = onRefresh,
            )
        }
        if (state.isEmpty) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    icon = Icons.Filled.Map,
                    title = stringResource(R.string.trains_route_empty_title),
                    message = stringResource(R.string.trains_route_empty_message),
                )
                Button(onClick = onRefresh, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.trains_route_empty_fetch))
                }
            }
        } else if (!state.loading) {
            RouteContent(state = state)
        }
    }
}

@Composable
private fun RouteContent(
    state: TrainRouteUiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "header") { RouteHeader(state = state) }
        state.daySections.forEach { section ->
            if (state.isMultiDay) {
                item(key = "day-${section.day}") {
                    Text(
                        text = stringResource(R.string.trains_route_day_header, section.day),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            items(section.stops, key = TrainRouteStop::id) { stop ->
                RouteStopRow(stop = stop)
            }
        }
    }
}

@Composable
private fun RouteHeader(
    state: TrainRouteUiState,
    modifier: Modifier = Modifier,
) {
    val ticket = state.ticket
    ClearTravelCard(modifier = modifier.fillMaxWidth()) {
        if (ticket != null) {
            Text(
                text = listOf(ticket.trainNumber, ticket.trainName).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        state.duration?.let { duration ->
            Text(
                text =
                    stringResource(
                        R.string.trains_detail_duration_value,
                        duration.toHours(),
                        duration.toMinutes() % MINUTES_PER_HOUR,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.fetchedAt?.let { fetched ->
            Text(
                text =
                    stringResource(
                        R.string.trains_route_last_fetched,
                        FETCHED_FORMAT.format(fetched.atZone(ZoneId.systemDefault())),
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RouteStopRow(
    stop: TrainRouteStop,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stop.stationName, style = MaterialTheme.typography.bodyLarge)
            val halt = haltMinutes(stop)
            val extras =
                listOfNotNull(
                    stop.platform.takeIf(String::isNotBlank)?.let {
                        stringResource(R.string.trains_detail_stop_platform, it)
                    },
                    halt?.takeIf { it > 0 }?.let {
                        stringResource(R.string.trains_route_stop_halt, it)
                    },
                )
            if (extras.isNotEmpty()) {
                Text(
                    text = extras.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (stop.arrival.isNotBlank() || stop.departure.isNotBlank()) {
            Text(
                text = stringResource(R.string.trains_detail_stop_times, stop.arrival, stop.departure),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
