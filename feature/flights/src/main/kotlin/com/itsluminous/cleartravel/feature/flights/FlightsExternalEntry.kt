package com.itsluminous.cleartravel.feature.flights

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.itsluminous.cleartravel.feature.flights.form.FlightFormScreen
import com.itsluminous.cleartravel.feature.flights.status.StatusCheckScreen

/**
 * What an EXTERNAL entry into the flights add form carries (integration contract for
 * the app shell's share-sheet file intake). Each variant maps onto one existing
 * import path of `FlightFormViewModel` — no extraction logic is duplicated.
 */
sealed interface FlightsEntryRequest {
    /** Shared PDF/image the user confirmed is a boarding pass → BCBP/OCR prefill. */
    data class BoardingPass(
        val uri: String,
    ) : FlightsEntryRequest

    /** Shared PDF/image the user confirmed is a booking confirmation (ADR-017). */
    data class BookingConfirmation(
        val uri: String,
    ) : FlightsEntryRequest
}

/**
 * PUBLIC external entry point (integration contract for the app module): the
 * add-flight form prefilled per [request]. "Save & check status" chains into the
 * interactive status-check screen exactly like the in-tab flow; [onDone] fires when
 * the user cancels, after a plain save, or when the status check closes.
 */
@Composable
fun FlightsExternalEntry(
    request: FlightsEntryRequest,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var checkingFlightId by remember { mutableStateOf<String?>(null) }
    val checking = checkingFlightId
    if (checking != null) {
        StatusCheckScreen(
            flightId = checking,
            onClose = { onDone() },
            modifier = modifier,
        )
    } else {
        FlightFormScreen(
            editId = null,
            importUri = (request as? FlightsEntryRequest.BoardingPass)?.uri,
            bookingUri = (request as? FlightsEntryRequest.BookingConfirmation)?.uri,
            onClose = onDone,
            onSavedAndCheck = { id -> checkingFlightId = id },
            modifier = modifier,
        )
    }
}
