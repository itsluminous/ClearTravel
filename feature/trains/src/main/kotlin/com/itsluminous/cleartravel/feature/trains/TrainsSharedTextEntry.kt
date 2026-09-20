package com.itsluminous.cleartravel.feature.trains

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
 * PUBLIC share-sheet entry point (integration contract for the app module).
 *
 * The app manifest's `ACTION_SEND` text intent filter is deferred to a later
 * integration stage; when it lands, the integrator routes the shared text here:
 *
 * ```
 * TrainsSharedTextEntry(
 *     sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty(),
 *     onDone = { /* finish / navigate back */ },
 * )
 * ```
 *
 * Renders the add-ticket form prefilled from [sharedText] via the IRCTC SMS/email
 * parser (per-field confidence markers, never saved blind). [onDone] fires after the
 * ticket is saved OR the user cancels.
 */
@Composable
fun TrainsSharedTextEntry(
    sharedText: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TrainTicketFormViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(sharedText) { viewModel.startFromText(sharedText) }
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
