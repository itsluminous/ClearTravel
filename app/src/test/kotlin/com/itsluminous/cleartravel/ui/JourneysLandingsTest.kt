package com.itsluminous.cleartravel.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.flights.FlightsLandingAction
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction
import org.junit.Test

/**
 * ADR-029: a Journeys landing fires EXACTLY once per deep-link nonce and never on
 * plain tab entry — the "Journeys tab auto-opens a detail sheet" regression came from
 * a never-cleared landing replaying into the segment on every re-composition.
 */
class JourneysLandingsTest {
    private val landings = JourneysLandings()

    @Test
    fun `plain tab entry has nothing to land on`() {
        assertThat(landings.trains).isNull()
        assertThat(landings.flights).isNull()
    }

    @Test
    fun `train link lands on the trains segment only`() {
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t1", nonce = 1L))

        assertThat(landings.trains).isEqualTo(SegmentLanding("t1", TrainsLandingAction.OPEN_DETAIL, 1L))
        assertThat(landings.flights).isNull()
    }

    @Test
    fun `flight link carries its own action`() {
        landings.land(
            JourneysDeepLink(
                DeepLinkContract.TARGET_FLIGHT,
                "f1",
                flightsAction = FlightsLandingAction.DUPLICATE_FLIGHT,
                nonce = 2L,
            ),
        )

        assertThat(landings.flights).isEqualTo(SegmentLanding("f1", FlightsLandingAction.DUPLICATE_FLIGHT, 2L))
        assertThat(landings.trains).isNull()
    }

    @Test
    fun `consuming the landing clears it, and a second consume is a no-op`() {
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t1", nonce = 1L))

        landings.consumeTrains(1L)
        assertThat(landings.trains).isNull()

        landings.consumeTrains(1L)
        assertThat(landings.trains).isNull()
    }

    @Test
    fun `a stale consume never cancels a newer landing`() {
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t1", nonce = 1L))
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t2", nonce = 2L))

        landings.consumeTrains(1L)

        assertThat(landings.trains).isEqualTo(SegmentLanding("t2", TrainsLandingAction.OPEN_DETAIL, 2L))
    }

    @Test
    fun `same entity linked twice re-lands under the new nonce`() {
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t1", nonce = 1L))
        landings.consumeTrains(1L)

        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t1", nonce = 2L))

        assertThat(landings.trains?.nonce).isEqualTo(2L)
    }

    @Test
    fun `an entity-less link (cancelled entry, pick mode) supersedes a pending landing`() {
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_FLIGHT, "f1", nonce = 1L))

        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_FLIGHT, entityId = null, nonce = 2L))

        assertThat(landings.flights).isNull()
    }

    @Test
    fun `consuming one segment leaves the other untouched`() {
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_TRAIN, "t1", nonce = 1L))
        landings.land(JourneysDeepLink(DeepLinkContract.TARGET_FLIGHT, "f1", nonce = 2L))

        landings.consumeFlights(2L)

        assertThat(landings.trains?.entityId).isEqualTo("t1")
        assertThat(landings.flights).isNull()
    }
}
