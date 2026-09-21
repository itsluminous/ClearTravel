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
 * How an external entry ended (ADR-024/025). The shell uses it to land the user on
 * the Journeys tab's Flights segment with the right follow-up instead of dropping
 * them back on whatever tab was open.
 */
sealed interface FlightsEntryResult {
    /** The user backed out; nothing was written. */
    data object Cancelled : FlightsEntryResult

    /** A journey was saved (plain save, or "Save & check status" after the check closed). */
    data class Saved(
        val flightId: String,
    ) : FlightsEntryResult

    /** Save refused — a live journey with the same airline + number + date exists (ADR-025). */
    data class DuplicateFlight(
        val existingFlightId: String,
    ) : FlightsEntryResult
}

/**
 * What the Flights segment should do on arrival for a given journey (integration
 * contract for the Journeys shell, ADR-024/025). Mirrors [FlightsEntryResult] so
 * the shell can forward an entry outcome without knowing the segment's internals.
 */
enum class FlightsLandingAction {
    /** Expand the journey's detail sheet (notification deep links, plain saves). */
    OPEN_DETAIL,

    /** Show the list with a duplicate notice whose "View" opens the EXISTING journey. */
    DUPLICATE_FLIGHT,
}

/**
 * PUBLIC external entry point (integration contract for the app module): the
 * add-flight form prefilled per [request]. "Save & check status" chains into the
 * interactive status-check screen exactly like the in-tab flow; [onDone] fires
 * exactly once with how the entry ended — saved (after a plain save or when the
 * status check closes), refused as a duplicate, or cancelled — so the shell can land
 * on Journeys/Flights showing the right journey (ADR-024/025).
 */
@Composable
fun FlightsExternalEntry(
    request: FlightsEntryRequest,
    onDone: (FlightsEntryResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var checkingFlightId by remember { mutableStateOf<String?>(null) }
    val checking = checkingFlightId
    if (checking != null) {
        StatusCheckScreen(
            flightId = checking,
            onClose = { onDone(FlightsEntryResult.Saved(checking)) },
            modifier = modifier,
        )
    } else {
        FlightFormScreen(
            editId = null,
            importUri = (request as? FlightsEntryRequest.BoardingPass)?.uri,
            bookingUri = (request as? FlightsEntryRequest.BookingConfirmation)?.uri,
            onClose = { onDone(FlightsEntryResult.Cancelled) },
            onSaved = { id -> onDone(FlightsEntryResult.Saved(id)) },
            onSavedAndCheck = { id -> checkingFlightId = id },
            onDuplicate = { existingId -> onDone(FlightsEntryResult.DuplicateFlight(existingId)) },
            modifier = modifier,
        )
    }
}
