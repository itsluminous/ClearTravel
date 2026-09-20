package com.itsluminous.cleartravel.feature.flights

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState

/**
 * The Flights segment of the Journeys tab. The app module places this next to the
 * Trains segment under a segmented control; this module never references
 * `feature:trains`. Skeleton: empty state until the Flights milestone lands the
 * journey list.
 */
@Composable
fun FlightsContent(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Flight,
        title = stringResource(R.string.flights_empty_title),
        message = stringResource(R.string.flights_empty_message),
        modifier = modifier,
    )
}
