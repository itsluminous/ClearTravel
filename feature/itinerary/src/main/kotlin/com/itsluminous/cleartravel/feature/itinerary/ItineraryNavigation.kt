package com.itsluminous.cleartravel.feature.itinerary

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState

/** Route of the Trips tab root (the trip list / itinerary feature). */
const val TRIPS_ROUTE = "trips"

/** Trips tab graph. Skeleton: the empty trip list until the Trips milestone. */
fun NavGraphBuilder.tripsGraph() {
    composable(TRIPS_ROUTE) {
        TripsScreen()
    }
}

@Composable
internal fun TripsScreen(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Map,
        title = stringResource(R.string.itinerary_empty_title),
        message = stringResource(R.string.itinerary_empty_message),
        modifier = modifier,
    )
}
