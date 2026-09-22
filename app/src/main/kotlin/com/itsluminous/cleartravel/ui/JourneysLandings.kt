package com.itsluminous.cleartravel.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.flights.FlightsLandingAction
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction

/**
 * A one-shot landing on a Journeys segment (ADR-029): act on [entityId] per [action],
 * for the deep link identified by [nonce]. It exists only until the segment reports
 * it has acted; a landing is never re-derived from persisted state.
 */
data class SegmentLanding<A>(
    val entityId: String,
    val action: A,
    val nonce: Long,
)

/**
 * The pending per-segment landings of the Journeys tab, derived from a
 * [JourneysDeepLink] and consumed exactly once by the segment that acts on them
 * (ADR-029). Pure Kotlin so the once-only semantics are unit-tested without Compose.
 *
 * Why this exists: the landing used to be two `rememberSaveable` fields that were set
 * by a deep link and NEVER cleared. Every re-entry of the Journeys tab (bottom-bar
 * revisit restores the saved state; a Trains ↔ Flights toggle re-creates the segment)
 * re-fed the stale id into the segment's landing effect, which dutifully re-opened
 * the ticket's detail sheet with no tap — the reported "Journeys tab auto-opens a
 * sheet" bug. Here a landing (a) is plain in-memory state, so plain tab entry finds
 * nothing to fire, and (b) is cleared by the segment via [consumeTrains] /
 * [consumeFlights] the moment it acts, keyed by nonce so a consume for an older link
 * cannot cancel a newer one.
 */
class JourneysLandings {
    var trains by mutableStateOf<SegmentLanding<TrainsLandingAction>?>(null)
        private set

    var flights by mutableStateOf<SegmentLanding<FlightsLandingAction>?>(null)
        private set

    /**
     * Records the landing [link] asks for. A link WITHOUT an entity (a cancelled
     * external entry, a pick-mode request) lands on the bare segment and therefore
     * supersedes any still-pending landing for that segment.
     */
    fun land(link: JourneysDeepLink) {
        when (link.target) {
            DeepLinkContract.TARGET_TRAIN ->
                trains = link.entityId?.let { SegmentLanding(it, link.trainsAction, link.nonce) }
            DeepLinkContract.TARGET_FLIGHT ->
                flights = link.entityId?.let { SegmentLanding(it, link.flightsAction, link.nonce) }
        }
    }

    /** The Trains segment has acted on the landing with [nonce]; a stale nonce is ignored. */
    fun consumeTrains(nonce: Long) {
        if (trains?.nonce == nonce) trains = null
    }

    /** The Flights segment has acted on the landing with [nonce]; a stale nonce is ignored. */
    fun consumeFlights(nonce: Long) {
        if (flights?.nonce == nonce) flights = null
    }
}
