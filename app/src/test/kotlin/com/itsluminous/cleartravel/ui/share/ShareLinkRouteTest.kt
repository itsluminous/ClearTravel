package com.itsluminous.cleartravel.ui.share

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.share.ShareKind
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.ShareLinkError
import com.itsluminous.cleartravel.core.data.share.SharePayloadMappers
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

/** ADR-039: MainActivity's share-link routing decision, kind by kind. */
class ShareLinkRouteTest {
    private fun url(payload: com.itsluminous.cleartravel.core.data.share.SharePayload) =
        (ShareLinkCodec.buildShareUrl(payload) as ShareUrlResult.Ok).url

    @Test
    fun `flight links go to the prefilled add form`() {
        val payload = SharePayloadMappers.toPayload(Fixtures.flightJourney())

        assertThat(routeShareLink(url(payload))).isEqualTo(ShareLinkRoute.Flight(payload))
    }

    @Test
    fun `trip and checklist links go to the import confirm`() {
        val trip = SharePayloadMappers.toPayload(Fixtures.trip(), listOf(Fixtures.itineraryItem()))
        val checklist = SharePayloadMappers.toPayload(Fixtures.checklist(), listOf(Fixtures.checklistItem()))

        assertThat(routeShareLink(url(trip))).isEqualTo(ShareLinkRoute.Trip(trip))
        assertThat(routeShareLink(ShareLinkCodec.customUrl(ShareKind.CHECKLIST, ShareLinkCodec.encode(checklist))))
            .isEqualTo(ShareLinkRoute.Checklist(checklist))
    }

    @Test
    fun `undecodable share links are routed to the failure dialog, other links are ignored`() {
        assertThat(routeShareLink("https://cleartravel.itsluminous.com/share/trip/garbage!!"))
            .isEqualTo(ShareLinkRoute.Failed(ShareLinkError.CORRUPTED))
        // PNR links (ADR-020) and arbitrary URLs are not share links: the caller falls through.
        assertThat(routeShareLink("https://cleartravel.itsluminous.com/pnr/8553674906")).isNull()
        assertThat(routeShareLink("https://example.com")).isNull()
        assertThat(routeShareLink(null)).isNull()
    }
}
