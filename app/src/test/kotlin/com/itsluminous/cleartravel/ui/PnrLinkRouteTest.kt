package com.itsluminous.cleartravel.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.notifications.DeepLinkContract
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.trains.TrainsLandingAction
import org.junit.Test
import java.time.Instant

/** ADR-044 §7: a PNR link opens the status CHECK of a ticket you already have, the add form otherwise. */
class PnrLinkRouteTest {
    private val pnr = "8524167890"

    @Test
    fun `live non-archived ticket with the PNR routes to its PNR check`() {
        val ticket = Fixtures.trainTicket(id = "t1", pnr = pnr)

        val route = PnrLinkRoute.resolve(pnr, ticket)

        assertThat(route).isEqualTo(PnrLinkRoute.CheckExisting("t1"))
        val landing = PnrLinkRoute.landing(route as PnrLinkRoute.CheckExisting)
        assertThat(landing.target).isEqualTo(DeepLinkContract.TARGET_TRAIN)
        assertThat(landing.entityId).isEqualTo("t1")
        assertThat(landing.trainsAction).isEqualTo(TrainsLandingAction.OPEN_PNR_CHECK)
    }

    @Test
    fun `unknown, archived or tombstoned PNR routes to the add form carrying the PNR`() {
        assertThat(PnrLinkRoute.resolve(pnr, null)).isEqualTo(PnrLinkRoute.AddNew(pnr))
        assertThat(PnrLinkRoute.resolve(pnr, Fixtures.trainTicket(pnr = pnr, archived = true))).isEqualTo(PnrLinkRoute.AddNew(pnr))
        assertThat(
            PnrLinkRoute.resolve(pnr, Fixtures.trainTicket(pnr = pnr, deletedAt = Instant.EPOCH)),
        ).isEqualTo(PnrLinkRoute.AddNew(pnr))
    }
}
