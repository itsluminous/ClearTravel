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
import com.itsluminous.cleartravel.feature.flights.FlightsContent
import com.itsluminous.cleartravel.feature.trains.TrainsContent

/** Route of the Journeys tab root (trains + flights, segmented). */
const val JOURNEYS_ROUTE = "journeys"

/**
 * Journeys tab graph. This screen lives in the app module because it is the ONE place
 * `feature:trains` and `feature:flights` are composed side by side — the features
 * themselves never depend on each other.
 */
fun NavGraphBuilder.journeysGraph() {
    composable(JOURNEYS_ROUTE) {
        JourneysScreen()
    }
}

private enum class JourneysSegment {
    TRAINS,
    FLIGHTS,
}

@Composable
private fun JourneysScreen(modifier: Modifier = Modifier) {
    var segment by rememberSaveable { mutableStateOf(JourneysSegment.TRAINS) }

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
            JourneysSegment.TRAINS -> TrainsContent()
            JourneysSegment.FLIGHTS -> FlightsContent()
        }
    }
}
