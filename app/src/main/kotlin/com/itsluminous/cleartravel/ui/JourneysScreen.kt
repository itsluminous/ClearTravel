package com.itsluminous.cleartravel.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.itsluminous.cleartravel.R
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddRequest
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddResult
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.flights.FlightsAddRequest
import com.itsluminous.cleartravel.feature.flights.FlightsContent
import com.itsluminous.cleartravel.feature.flights.FlightsLandingAction
import com.itsluminous.cleartravel.feature.trains.TrainsAddRequest
import com.itsluminous.cleartravel.feature.trains.TrainsContent
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction

/** Route of the Journeys tab root (trains + flights, segmented). */
const val JOURNEYS_ROUTE = "journeys"

/** Test tag on the Trains|Flights segmented control (e2e asserts it is docked at the bottom). */
const val JOURNEYS_SEGMENT_TEST_TAG = "journeys_segment"

/**
 * Journeys tab graph. This screen lives in the app module because it is the ONE place
 * `feature:trains` and `feature:flights` are composed side by side — the features
 * themselves never depend on each other. [deepLink] carries a pending notification
 * deep link; the screen selects the matching segment, forwards the entity id to the
 * feature's detail hook, and reports consumption via [onDeepLinkConsumed]. ADR-028:
 * a deep link carrying an add request puts the segment into pick mode and the
 * outcome comes back through [onJourneyAddDone]; a "Part of" row tapped in a detail
 * sheet is reported through [onOpenTrip].
 */
fun NavGraphBuilder.journeysGraph(
    deepLink: JourneysDeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onJourneyAddDone: (JourneyAddResult) -> Unit = {},
    onOpenTrip: (tripId: String) -> Unit = {},
) {
    composable(JOURNEYS_ROUTE) {
        JourneysScreen(
            deepLink = deepLink,
            onDeepLinkConsumed = onDeepLinkConsumed,
            onJourneyAddDone = onJourneyAddDone,
            onOpenTrip = onOpenTrip,
        )
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
    onJourneyAddDone: (JourneyAddResult) -> Unit,
    onOpenTrip: (tripId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var segment by rememberSaveable { mutableStateOf(JourneysSegment.TRAINS) }
    // ADR-029: the per-segment landings a deep link asks for. Deliberately NOT
    // saveable (they used to be, and were never cleared — so every tab revisit or
    // segment toggle replayed the last landing and auto-opened a detail sheet). A
    // landing lives only while this screen is composed and is consumed by the segment
    // the moment it acts on it.
    val landings = remember { JourneysLandings() }
    // ADR-028 pick mode. Deliberately NOT saveable: the request lives only while this
    // screen is composed. Completing it navigates away (disposing it); a manual tab
    // tap does too, and the itinerary form cancels the stale request on its return —
    // so a re-entered Journeys tab must never re-open the add sheet for it.
    var addRequest by remember { mutableStateOf<JourneyAddRequest?>(null) }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            when (deepLink.target) {
                DeepLinkContract.TARGET_TRAIN -> segment = JourneysSegment.TRAINS
                DeepLinkContract.TARGET_FLIGHT -> segment = JourneysSegment.FLIGHTS
            }
            landings.land(deepLink)
            addRequest = deepLink.addRequest
            onDeepLinkConsumed()
        }
    }

    // The segmented control sits at the BOTTOM of the tab, directly above the app's
    // NavigationBar, so the Trains|Flights switch is in thumb reach next to the tab
    // bar (user steering); the segment content takes the rest of the height.
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (segment) {
                JourneysSegment.TRAINS -> {
                    val landing = landings.trains
                    TrainsContent(
                        initialTicketId = landing?.entityId,
                        initialAction = landing?.action ?: TrainsLandingAction.OPEN_DETAIL,
                        landingNonce = landing?.nonce,
                        onLandingConsumed = { nonce -> landings.consumeTrains(nonce) },
                        addRequest = addRequest?.takeIf { it.type == JourneyType.TRAIN }?.let { TrainsAddRequest(nonce = it.nonce) },
                        onAddRequestDone = { result ->
                            addRequest?.let { request ->
                                addRequest = null
                                onJourneyAddDone(JourneyPickRouting.resultFor(request, result))
                            }
                        },
                        onOpenTrip = onOpenTrip,
                    )
                }
                JourneysSegment.FLIGHTS -> {
                    val landing = landings.flights
                    FlightsContent(
                        initialFlightId = landing?.entityId,
                        initialAction = landing?.action ?: FlightsLandingAction.OPEN_DETAIL,
                        landingNonce = landing?.nonce,
                        onLandingConsumed = { nonce -> landings.consumeFlights(nonce) },
                        addRequest = addRequest?.takeIf { it.type == JourneyType.FLIGHT }?.let { FlightsAddRequest(nonce = it.nonce) },
                        onAddRequestDone = { result ->
                            addRequest?.let { request ->
                                addRequest = null
                                onJourneyAddDone(JourneyPickRouting.resultFor(request, result))
                            }
                        },
                        onOpenTrip = onOpenTrip,
                    )
                }
            }
        }
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(JOURNEYS_SEGMENT_TEST_TAG),
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
    }
}
