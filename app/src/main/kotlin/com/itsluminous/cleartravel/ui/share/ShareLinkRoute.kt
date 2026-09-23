package com.itsluminous.cleartravel.ui.share

import com.itsluminous.cleartravel.core.data.share.ChecklistSharePayload
import com.itsluminous.cleartravel.core.data.share.FlightSharePayload
import com.itsluminous.cleartravel.core.data.share.ShareDecodeResult
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.ShareLinkError
import com.itsluminous.cleartravel.core.data.share.TripSharePayload

/**
 * What the shell does with an incoming share link (ADR-039), decided PURELY from the
 * decoded payload: flights go straight to the prefilled add form (the form IS the
 * review), trips and checklists go to the import confirm dialog, failures to an
 * explanatory dialog.
 */
sealed interface ShareLinkRoute {
    data class Flight(
        val payload: FlightSharePayload,
    ) : ShareLinkRoute

    data class Trip(
        val payload: TripSharePayload,
    ) : ShareLinkRoute

    data class Checklist(
        val payload: ChecklistSharePayload,
    ) : ShareLinkRoute

    data class Failed(
        val error: ShareLinkError,
    ) : ShareLinkRoute
}

/** Routes a recognised share link; null when [link] is not a share link at all. */
fun routeShareLink(link: String?): ShareLinkRoute? =
    when (val decoded = ShareLinkCodec.decode(link)) {
        null -> null
        is ShareDecodeResult.Failed -> ShareLinkRoute.Failed(decoded.error)
        is ShareDecodeResult.Ok ->
            when (val payload = decoded.payload) {
                is FlightSharePayload -> ShareLinkRoute.Flight(payload)
                is TripSharePayload -> ShareLinkRoute.Trip(payload)
                is ChecklistSharePayload -> ShareLinkRoute.Checklist(payload)
            }
    }
