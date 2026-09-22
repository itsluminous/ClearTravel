package com.itsluminous.cleartravel.feature.itinerary.detail

import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.labelRes
import com.itsluminous.cleartravel.feature.itinerary.logic.formatLatLng
import com.itsluminous.cleartravel.feature.itinerary.logic.journeyIdFallback

/**
 * Bottom sheet showing EVERYTHING about one itinerary item: note/fun facts, link
 * (opens the browser), category, coordinates, commute route + linked journey (with
 * an "Open in Journeys" action, ADR-028). Shared by the timeline cards and the map
 * markers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItineraryItemSheet(
    item: ItineraryItem,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenLinkedJourney: (JourneyType, String) -> Unit = { _, _ -> },
    /** Resolved label of the linked journey (`12951`, `6E 2001`); null falls back to the id prefix. */
    linkedJourneyLabel: String? = null,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(text = item.name, style = MaterialTheme.typography.titleLarge)
            if (item.type == ItineraryItemType.PLACE) {
                SheetField(
                    labelRes = R.string.itinerary_sheet_category,
                    value = stringResource(item.category.labelRes()),
                )
                if (item.latitude != null && item.longitude != null) {
                    SheetField(
                        labelRes = R.string.itinerary_sheet_coordinates,
                        value = formatLatLng(item.latitude!!, item.longitude!!),
                    )
                }
            } else {
                SheetField(
                    labelRes = R.string.itinerary_sheet_mode,
                    value = stringResource(item.commuteMode.labelRes()),
                )
                SheetField(
                    labelRes = R.string.itinerary_sheet_route,
                    value = stringResource(R.string.itinerary_commute_route, item.fromName, item.toName),
                )
                val journeyId = item.linkedJourneyId
                val journeyType = item.linkedJourneyType
                if (journeyId != null && journeyType != null) {
                    SheetField(
                        labelRes = R.string.itinerary_sheet_linked_journey,
                        value =
                            stringResource(
                                when (journeyType) {
                                    JourneyType.TRAIN -> R.string.itinerary_linked_train
                                    JourneyType.FLIGHT -> R.string.itinerary_linked_flight
                                },
                                linkedJourneyLabel ?: journeyIdFallback(journeyId),
                            ),
                    )
                    TextButton(onClick = { onOpenLinkedJourney(journeyType, journeyId) }) {
                        Text(stringResource(R.string.itinerary_open_in_journeys))
                    }
                }
            }
            if (item.plannedTime.isNotBlank()) {
                SheetField(labelRes = R.string.itinerary_sheet_time, value = item.plannedTime)
            }
            if (item.note.isNotBlank()) {
                SheetField(labelRes = R.string.itinerary_sheet_note, value = item.note)
            }
            if (item.link.isNotBlank()) {
                SheetField(labelRes = R.string.itinerary_sheet_link, value = item.link)
            }
            // "View in map" works for any item that has a stored link OR
            // coordinates (picked on the map / entered manually): the stored link
            // wins, else a maps URL is built from the coordinates.
            val lat = item.latitude
            val lng = item.longitude
            val mapTarget =
                item.link.ifBlank {
                    if (lat != null && lng != null) "https://maps.google.com/?q=$lat,$lng" else ""
                }
            if (mapTarget.isNotBlank()) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, mapTarget.toUri()))
                        }
                    },
                ) { Text(stringResource(R.string.itinerary_open_link)) }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp)) {
                Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.itinerary_edit_item))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) { Text(stringResource(R.string.itinerary_delete_item)) }
            }
        }
    }
}

@Composable
private fun SheetField(
    @StringRes labelRes: Int,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 12.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}
