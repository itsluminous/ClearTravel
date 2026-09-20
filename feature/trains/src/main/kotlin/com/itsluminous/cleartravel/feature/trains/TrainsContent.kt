package com.itsluminous.cleartravel.feature.trains

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Train
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState

/**
 * The Trains segment of the Journeys tab. The app module places this next to the
 * Flights segment under a segmented control; this module never references
 * `feature:flights`. Skeleton: empty state until the Trains milestone lands the
 * ticket list.
 */
@Composable
fun TrainsContent(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Train,
        title = stringResource(R.string.trains_empty_title),
        message = stringResource(R.string.trains_empty_message),
        modifier = modifier,
    )
}
