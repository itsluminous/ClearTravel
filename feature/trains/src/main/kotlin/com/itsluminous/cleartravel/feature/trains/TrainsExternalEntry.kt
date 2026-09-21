package com.itsluminous.cleartravel.feature.trains

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.feature.trains.form.TrainFormEvent
import com.itsluminous.cleartravel.feature.trains.form.TrainTicketFormScreen
import com.itsluminous.cleartravel.feature.trains.form.TrainTicketFormViewModel

/**
 * What an EXTERNAL entry into the trains add form carries (integration contract for
 * the app shell — share sheet, deep links). Each variant maps onto one existing
 * prefill path of `TrainTicketFormViewModel`; nothing is ever saved blind.
 */
sealed interface TrainsEntryRequest {
    /** Shared IRCTC SMS/email text → SMS parser prefill. */
    data class Text(
        val text: String,
    ) : TrainsEntryRequest

    /** Shared PDF/image (the user confirmed it is a train ticket) → OCR prefill. */
    data class File(
        val uri: Uri,
    ) : TrainsEntryRequest

    /** Incoming PNR share link (ADR-020) → form carrying only the PNR. */
    data class Pnr(
        val pnr: String,
    ) : TrainsEntryRequest
}

/**
 * How an external entry ended (ADR-024). The shell uses it to land the user on the
 * Journeys tab's Trains segment with the right follow-up instead of dropping them
 * back on whatever tab was open — previously the default Trips tab, which looked
 * like the share had added nothing.
 */
sealed interface TrainsEntryResult {
    /** The user backed out; nothing was written. */
    data object Cancelled : TrainsEntryResult

    /**
     * A ticket was saved. [openPnrCheck] is the PNR-only quick-add signal (ADR-023):
     * the landing screen should run the PNR check so the first fetch backfills it.
     */
    data class Saved(
        val ticketId: String,
        val openPnrCheck: Boolean,
    ) : TrainsEntryResult

    /** Save refused — a live ticket with the same PNR already exists (ADR-024). */
    data class DuplicatePnr(
        val existingTicketId: String,
    ) : TrainsEntryResult
}

/**
 * What the Trains segment should do on arrival for a given ticket (integration
 * contract for the Journeys shell, ADR-024). Mirrors [TrainsEntryResult] so the
 * shell can forward an entry outcome without knowing the segment's internals.
 */
enum class TrainsLandingAction {
    /** Expand the ticket's detail sheet (notification deep links, plain saves). */
    OPEN_DETAIL,

    /** Open the PNR check for the ticket (PNR-only quick add, ADR-023). */
    OPEN_PNR_CHECK,

    /** Expand the EXISTING ticket and explain that the PNR was already present. */
    DUPLICATE_PNR,
}

/**
 * PUBLIC external entry point (integration contract for the app module): renders
 * the add-ticket form prefilled per [request]; [onDone] fires exactly once with how
 * the entry ended — saved, refused as a duplicate, or cancelled. The app shell
 * renders this over its normal UI and lands on Journeys/Trains afterwards.
 */
@Composable
fun TrainsExternalEntry(
    request: TrainsEntryRequest,
    onDone: (TrainsEntryResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TrainTicketFormViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(request) {
        when (request) {
            is TrainsEntryRequest.Text -> viewModel.startFromText(request.text)
            is TrainsEntryRequest.File -> viewModel.startFromUri(request.uri)
            is TrainsEntryRequest.Pnr -> viewModel.startWithPnr(request.pnr)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainFormEvent.Saved ->
                    onDone(TrainsEntryResult.Saved(ticketId = event.ticketId, openPnrCheck = event.openPnrCheck))
                is TrainFormEvent.DuplicatePnr -> onDone(TrainsEntryResult.DuplicatePnr(event.existingTicketId))
                is TrainFormEvent.PrefillEmpty ->
                    snackbarHostState.showSnackbar(context.getString(R.string.trains_import_failed))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        TrainTicketFormScreen(
            state = state,
            viewModel = viewModel,
            onCancel = { onDone(TrainsEntryResult.Cancelled) },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
