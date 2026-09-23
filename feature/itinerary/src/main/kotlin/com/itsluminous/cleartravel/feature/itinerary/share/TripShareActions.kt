package com.itsluminous.cleartravel.feature.itinerary.share

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.itsluminous.cleartravel.core.designsystem.component.CardShare
import com.itsluminous.cleartravel.feature.itinerary.R

/**
 * Turns a [TripShareOutcome] into the share sheet (text + https link) or the
 * explanatory snackbar. One place so the list card and the detail top bar behave the
 * same (ADR-039).
 */
internal suspend fun handleTripShareOutcome(
    context: Context,
    snackbarHostState: SnackbarHostState,
    outcome: TripShareOutcome,
) {
    when (outcome) {
        is TripShareOutcome.Ready ->
            CardShare.send(
                context = context,
                text = context.getString(R.string.itinerary_share_text, outcome.tripName, outcome.url),
                chooserTitle = context.getString(R.string.itinerary_share_chooser_title),
            )
        TripShareOutcome.TooLong -> snackbarHostState.showSnackbar(context.getString(R.string.itinerary_share_too_long))
        TripShareOutcome.Missing -> snackbarHostState.showSnackbar(context.getString(R.string.itinerary_share_missing))
    }
}
