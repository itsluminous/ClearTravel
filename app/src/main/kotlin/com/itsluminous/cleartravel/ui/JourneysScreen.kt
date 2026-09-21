package com.itsluminous.cleartravel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.cleartravel.R
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.flights.FlightsContent
import com.itsluminous.cleartravel.feature.flights.FlightsLandingAction
import com.itsluminous.cleartravel.feature.trains.TrainsContent
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction

/** Route of the Journeys tab root (trains + flights, segmented). */
const val JOURNEYS_ROUTE = "journeys"

/**
 * Journeys tab graph. This screen lives in the app module because it is the ONE place
 * `feature:trains` and `feature:flights` are composed side by side — the features
 * themselves never depend on each other. [deepLink] carries a pending notification
 * deep link; the screen selects the matching segment, forwards the entity id to the
 * feature's detail hook, and reports consumption via [onDeepLinkConsumed].
 */
fun NavGraphBuilder.journeysGraph(
    deepLink: JourneysDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    composable(JOURNEYS_ROUTE) {
        JourneysScreen(deepLink = deepLink, onDeepLinkConsumed = onDeepLinkConsumed)
    }
}

private enum class JourneysSegment {
    TRAINS,
    FLIGHTS,
}

@Composable
private fun JourneysScreen(
    deepLink: JourneysDeepLink?,
    onDeepLinkConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var segment by rememberSaveable { mutableStateOf(JourneysSegment.TRAINS) }
    var trainDeepLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var trainAction by rememberSaveable { mutableStateOf(TrainsLandingAction.OPEN_DETAIL) }
    var flightDeepLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var flightAction by rememberSaveable { mutableStateOf(FlightsLandingAction.OPEN_DETAIL) }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            when (deepLink.target) {
                DeepLinkContract.TARGET_TRAIN -> {
                    segment = JourneysSegment.TRAINS
                    trainDeepLinkId = deepLink.entityId
                    trainAction = deepLink.trainsAction
                }
                DeepLinkContract.TARGET_FLIGHT -> {
                    segment = JourneysSegment.FLIGHTS
                    flightDeepLinkId = deepLink.entityId
                    flightAction = deepLink.flightsAction
                }
            }
            onDeepLinkConsumed()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            JourneysSegment.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = segment == entry,
                    onClick = { segment = entry },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = JourneysSegment.entries.size,
                        ),
                ) {
                    Text(
                        text =
                            stringResource(
                                when (entry) {
                                    JourneysSegment.TRAINS -> R.string.journeys_segment_trains
                                    JourneysSegment.FLIGHTS -> R.string.journeys_segment_flights
                                },
                            ),
                    )
                }
            }
        }
        when (segment) {
            JourneysSegment.TRAINS -> TrainsContent(initialTicketId = trainDeepLinkId, initialAction = trainAction)
            JourneysSegment.FLIGHTS -> FlightsContent(initialFlightId = flightDeepLinkId, initialAction = flightAction)
        }
    }
}
