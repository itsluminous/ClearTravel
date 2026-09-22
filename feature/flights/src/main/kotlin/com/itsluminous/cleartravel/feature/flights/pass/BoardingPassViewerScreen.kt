package com.itsluminous.cleartravel.feature.flights.pass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.itsluminous.cleartravel.core.designsystem.component.DocumentViewerScreen
import com.itsluminous.cleartravel.feature.flights.R

/**
 * Offline boarding-pass display for the gate (spec feature 2): the shared
 * full-brightness [DocumentViewerScreen] (hoisted into `core:designsystem` in
 * ADR-027; zoom/pan, rotate, share and save-a-copy added in ADR-030) under a
 * flights-owned title. Also used for booking confirmations via [titleRes].
 */
@Composable
fun BoardingPassViewerScreen(
    path: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Viewer reuse (ADR-017): booking confirmations pass their own title. */
    titleRes: Int = R.string.flights_pass_viewer_title,
) {
    DocumentViewerScreen(
        path = path,
        title = stringResource(titleRes),
        onClose = onClose,
        modifier = modifier,
    )
}
