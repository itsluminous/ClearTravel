package com.itsluminous.cleartravel.ui

import android.content.Intent
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.flights.FlightsEntryResult
import com.itsluminous.cleartravel.feature.flights.FlightsLandingAction
import com.itsluminous.cleartravel.feature.trains.TrainsEntryResult
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction

/**
 * A request to land on the Journeys tab: open [target]'s segment and, when
 * [entityId] is set, act on that entity — expand its detail sheet by default, or per
 * [trainsAction] / [flightsAction] for the respective segment (ADR-024/025). Built from notification deep links
 * (ADR-013's `DeepLinkContract` extras) and from external-entry outcomes (share
 * sheet / PNR links), so an added ticket is SHOWN instead of the app dropping back
 * on the Trips tab. [nonce] makes consecutive links to the same entity distinct so
 * effects re-fire.
 */
data class JourneysDeepLink(
    val target: String,
    val entityId: String?,
    val trainsAction: TrainsLandingAction = TrainsLandingAction.OPEN_DETAIL,
    val flightsAction: FlightsLandingAction = FlightsLandingAction.OPEN_DETAIL,
    val nonce: Long = System.nanoTime(),
) {
    companion object {
        /** Parses the contract extras from [intent]; null when absent/incomplete. */
        fun fromIntent(intent: Intent): JourneysDeepLink? {
            val target = intent.getStringExtra(DeepLinkContract.EXTRA_TARGET) ?: return null
            val entityId = intent.getStringExtra(DeepLinkContract.EXTRA_ENTITY_ID) ?: return null
            if (target != DeepLinkContract.TARGET_TRAIN && target != DeepLinkContract.TARGET_FLIGHT) return null
            return JourneysDeepLink(target = target, entityId = entityId)
        }

        /**
         * Landing for a finished trains external entry (ADR-024): always the Trains
         * segment; saved → detail (or the PNR check for a PNR-only quick add),
         * duplicate → the existing ticket with the duplicate notice, cancelled →
         * just the list.
         */
        fun forTrainsEntry(result: TrainsEntryResult): JourneysDeepLink =
            when (result) {
                is TrainsEntryResult.Cancelled ->
                    JourneysDeepLink(target = DeepLinkContract.TARGET_TRAIN, entityId = null)
                is TrainsEntryResult.Saved ->
                    JourneysDeepLink(
                        target = DeepLinkContract.TARGET_TRAIN,
                        entityId = result.ticketId,
                        trainsAction =
                            if (result.openPnrCheck) {
                                TrainsLandingAction.OPEN_PNR_CHECK
                            } else {
                                TrainsLandingAction.OPEN_DETAIL
                            },
                    )
                is TrainsEntryResult.DuplicatePnr ->
                    JourneysDeepLink(
                        target = DeepLinkContract.TARGET_TRAIN,
                        entityId = result.existingTicketId,
                        trainsAction = TrainsLandingAction.DUPLICATE_PNR,
                    )
            }

        /**
         * Landing for a finished flights external entry (ADR-025): always the Flights
         * segment; saved → detail sheet, duplicate → the list with the notice whose
         * "View" opens the existing journey, cancelled → just the list.
         */
        fun forFlightsEntry(result: FlightsEntryResult): JourneysDeepLink =
            when (result) {
                is FlightsEntryResult.Cancelled ->
                    JourneysDeepLink(target = DeepLinkContract.TARGET_FLIGHT, entityId = null)
                is FlightsEntryResult.Saved ->
                    JourneysDeepLink(target = DeepLinkContract.TARGET_FLIGHT, entityId = result.flightId)
                is FlightsEntryResult.DuplicateFlight ->
                    JourneysDeepLink(
                        target = DeepLinkContract.TARGET_FLIGHT,
                        entityId = result.existingFlightId,
                        flightsAction = FlightsLandingAction.DUPLICATE_FLIGHT,
                    )
            }
    }
}
