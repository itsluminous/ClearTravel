package com.itsluminous.cleartravel.feature.trains.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.feature.trains.list.StatusPillRow
import com.itsluminous.cleartravel.feature.trains.list.TicketBodyLines
import com.itsluminous.cleartravel.feature.trains.list.TicketHeaderBand
import java.time.Instant

/**
 * The self-contained card that is rendered OFF-SCREEN into the share image: header
 * band, title, PNR, class/quota and status pills — no action icons and NO "Updated X
 * ago" freshness line (both are meaningless in a picture read later by someone
 * else). Reuses the list card's pieces so the shared image otherwise looks exactly
 * like what the user sees in the app.
 */
@Composable
internal fun ShareTicketCard(
    ticket: TrainTicket,
    passengers: List<TrainPassenger>,
    departureTime: String?,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column {
            TicketHeaderBand(ticket = ticket, departureTime = departureTime)
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(
                    modifier =
                        Modifier
                            .width(6.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.tertiary),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)) {
                    TicketBodyLines(ticket = ticket, now = now, showFreshness = false)
                    StatusPillRow(passengers = passengers, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}
