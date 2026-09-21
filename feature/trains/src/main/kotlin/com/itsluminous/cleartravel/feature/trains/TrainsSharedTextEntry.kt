package com.itsluminous.cleartravel.feature.trains

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * PUBLIC share-sheet entry point (integration contract for the app module) for
 * shared IRCTC SMS/email TEXT — kept as a thin wrapper over [TrainsExternalEntry]
 * (which also covers shared files and PNR deep links) so existing call sites and
 * the README contract are untouched.
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
    TrainsExternalEntry(
        request = TrainsEntryRequest.Text(sharedText),
        onDone = onDone,
        modifier = modifier,
    )
}
