package com.itsluminous.cleartravel.feature.flights.form

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itsluminous.cleartravel.core.designsystem.component.AutoShrinkText
import com.itsluminous.cleartravel.core.designsystem.component.ClearTravelCard
import com.itsluminous.cleartravel.feature.flights.R
import com.itsluminous.cleartravel.feature.flights.list.formatDate

/** Test tag of the review sheet's confirm button (e2e). */
const val IMPORT_REVIEW_CONFIRM_TAG = "flights_import_review_confirm"

/**
 * ADR-045 review step: "Found N flights in this file" — one card per segment (airline +
 * number, route, date, time, PNR, seat) with a checkbox, all kept by default, and an
 * "Add N flights" button. Segments missing the direct-save minimum say so; confirming
 * with one of those walks the kept segments through the form instead of saving blind.
 * Dismissing writes nothing. A pure choice sheet, so it keeps the default tap-outside
 * dismissal (ADR-041).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlightImportReviewSheet(
    review: FlightImportReview,
    onToggle: (index: Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoShrinkText(
                text = pluralStringResource(R.plurals.flights_import_review_title, review.items.size, review.items.size),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text =
                    stringResource(
                        when (review.kind) {
                            FlightImportKind.BOARDING_PASS -> R.string.flights_import_review_hint_pass
                            FlightImportKind.BOOKING_CONFIRMATION -> R.string.flights_import_review_hint_booking
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            review.items.forEachIndexed { index, item ->
                ReviewItemCard(item = item, onToggle = { onToggle(index) })
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    AutoShrinkText(stringResource(R.string.flights_form_cancel))
                }
                Button(
                    onClick = onConfirm,
                    enabled = review.selectedCount > 0,
                    modifier = Modifier.weight(1f).testTag(IMPORT_REVIEW_CONFIRM_TAG),
                ) {
                    AutoShrinkText(
                        pluralStringResource(R.plurals.flights_import_review_confirm, review.selectedCount, review.selectedCount),
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: FlightImportReviewItem,
    onToggle: () -> Unit,
) {
    val form = item.form
    ClearTravelCard(modifier = Modifier.clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AutoShrinkText(
                    text = reviewTitle(form),
                    style = MaterialTheme.typography.titleMedium,
                )
                val details = reviewDetails(form)
                if (details.isNotEmpty()) {
                    AutoShrinkText(
                        text = details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minScale = 0.8f,
                    )
                }
                if (!item.isComplete) {
                    Text(
                        text = stringResource(R.string.flights_import_review_needs_details),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

/** `IX 2019 · BLR → LKO` (route part only when both airports are known). */
@Composable
private fun reviewTitle(form: FlightFormState): String {
    val flight = listOf(form.airlineIata, form.flightNumber).filter { it.isNotBlank() }.joinToString(" ")
    val route =
        if (form.depAirport.isNotBlank() && form.arrAirport.isNotBlank()) {
            "${form.depAirport} ${stringResource(R.string.flights_card_route_separator)} ${form.arrAirport}"
        } else {
            ""
        }
    return listOf(flight, route)
        .filter { it.isNotBlank() }
        .joinToString(stringResource(R.string.flights_import_review_separator))
        .ifBlank { stringResource(R.string.flights_import_review_unknown_flight) }
}

/** `Thu, 29 Oct 2026 · 10:30 · PNR R87T5G · Seat 12A` — only the parts the segment has. */
@Composable
private fun reviewDetails(form: FlightFormState): String =
    listOfNotNull(
        FlightFormState.parseDate(form.dateText)?.let { formatDate(it) },
        form.depTimeText.takeIf { it.isNotBlank() },
        form.pnr.takeIf { it.isNotBlank() }?.let { stringResource(R.string.flights_import_review_pnr, it) },
        form.seat.takeIf { it.isNotBlank() }?.let { stringResource(R.string.flights_import_review_seat, it) },
    ).joinToString(stringResource(R.string.flights_import_review_separator))
