package com.itsluminous.cleartravel.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.feature.trains.TrainsEntryResult
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction
import org.junit.Test

/**
 * Intake completion routing (ADR-024): every way a hosted external entry can end
 * must land on the Journeys tab's matching segment, never on the default Trips tab.
 */
class JourneysDeepLinkTest {
    @Test
    fun `saved train ticket lands on Trains with its detail sheet`() {
        val link = JourneysDeepLink.forTrainsEntry(TrainsEntryResult.Saved(ticketId = "t1", openPnrCheck = false))

        assertThat(link.target).isEqualTo(DeepLinkContract.TARGET_TRAIN)
        assertThat(link.entityId).isEqualTo("t1")
        assertThat(link.trainsAction).isEqualTo(TrainsLandingAction.OPEN_DETAIL)
    }

    @Test
    fun `PNR-only quick add lands on Trains opening the PNR check`() {
        val link = JourneysDeepLink.forTrainsEntry(TrainsEntryResult.Saved(ticketId = "t1", openPnrCheck = true))

        assertThat(link.entityId).isEqualTo("t1")
        assertThat(link.trainsAction).isEqualTo(TrainsLandingAction.OPEN_PNR_CHECK)
    }

    @Test
    fun `duplicate PNR lands on the EXISTING ticket with the duplicate notice`() {
        val link = JourneysDeepLink.forTrainsEntry(TrainsEntryResult.DuplicatePnr(existingTicketId = "existing"))

        assertThat(link.target).isEqualTo(DeepLinkContract.TARGET_TRAIN)
        assertThat(link.entityId).isEqualTo("existing")
        assertThat(link.trainsAction).isEqualTo(TrainsLandingAction.DUPLICATE_PNR)
    }

    @Test
    fun `cancelled entry still lands on the Trains segment without an entity`() {
        val link = JourneysDeepLink.forTrainsEntry(TrainsEntryResult.Cancelled)

        assertThat(link.target).isEqualTo(DeepLinkContract.TARGET_TRAIN)
        assertThat(link.entityId).isNull()
    }

    @Test
    fun `flights entry lands on Flights, with the saved flight when there is one`() {
        assertThat(JourneysDeepLink.forFlightsEntry("f1").target).isEqualTo(DeepLinkContract.TARGET_FLIGHT)
        assertThat(JourneysDeepLink.forFlightsEntry("f1").entityId).isEqualTo("f1")
        assertThat(JourneysDeepLink.forFlightsEntry(null).entityId).isNull()
    }
}
