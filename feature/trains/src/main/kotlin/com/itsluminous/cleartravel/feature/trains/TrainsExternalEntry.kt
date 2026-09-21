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
 * PUBLIC external entry point (integration contract for the app module): renders
 * the add-ticket form prefilled per [request]; [onDone] fires after the ticket is
 * saved OR the user cancels. The app shell renders this over its normal UI.
 */
@Composable
fun TrainsExternalEntry(
    request: TrainsEntryRequest,
    onDone: () -> Unit,
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
                is TrainFormEvent.Saved -> onDone()
                is TrainFormEvent.PrefillEmpty ->
                    snackbarHostState.showSnackbar(context.getString(R.string.trains_import_failed))
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        TrainTicketFormScreen(
            state = state,
            viewModel = viewModel,
            onCancel = onDone,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
