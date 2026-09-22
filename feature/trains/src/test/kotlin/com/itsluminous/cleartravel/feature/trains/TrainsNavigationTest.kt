package com.itsluminous.cleartravel.feature.trains

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The one-step-back map behind the Trains segment's system-back handling, and the route-fetch landing. */
class TrainsNavigationTest {
    @Test
    fun `bare list does not handle back`() {
        assertThat(trainsBackTarget(TrainsScreen.List, detailTicketId = null)).isNull()
    }

    @Test
    fun `detail sheet over the list dismisses to the list`() {
        assertThat(trainsBackTarget(TrainsScreen.List, detailTicketId = TICKET))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List, detailTicketId = null))
    }

    @Test
    fun `form cancels to the list`() {
        assertThat(trainsBackTarget(TrainsScreen.Form(FormEntry.Blank), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List))
        assertThat(trainsBackTarget(TrainsScreen.Form(FormEntry.Edit(TICKET)), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List))
    }

    @Test
    fun `pnr check closes to the list`() {
        assertThat(trainsBackTarget(TrainsScreen.PnrCheck(TICKET, pnr = "8524317690"), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List))
    }

    @Test
    fun `seat map opened from the list goes back to the list`() {
        assertThat(trainsBackTarget(TrainsScreen.SeatMap(TICKET, TRAIN), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List))
    }

    @Test
    fun `seat map opened from the detail sheet reopens the sheet`() {
        assertThat(trainsBackTarget(TrainsScreen.SeatMap(TICKET, TRAIN, fromDetail = true), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List, detailTicketId = TICKET))
    }

    @Test
    fun `offline route page goes back to its opener`() {
        assertThat(trainsBackTarget(TrainsScreen.RouteView(TICKET, TRAIN), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List))
        assertThat(trainsBackTarget(TrainsScreen.RouteView(TICKET, TRAIN, fromDetail = true), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List, detailTicketId = TICKET))
    }

    @Test
    fun `route fetch goes back to where it was opened from`() {
        val fromList = TrainsScreen.RouteFetch(TICKET, TRAIN)
        assertThat(trainsBackTarget(fromList, detailTicketId = null)).isEqualTo(TrainsBackTarget(TrainsScreen.List))

        val seatMap = TrainsScreen.SeatMap(TICKET, TRAIN, fromDetail = true)
        assertThat(trainsBackTarget(TrainsScreen.RouteFetch(TICKET, TRAIN, returnTo = seatMap), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(seatMap))

        val routeView = TrainsScreen.RouteView(TICKET, TRAIN)
        assertThat(trainsBackTarget(TrainsScreen.RouteFetch(TICKET, TRAIN, returnTo = routeView), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(routeView))
    }

    @Test
    fun `every non-list screen steps back somewhere`() {
        val screens =
            listOf(
                TrainsScreen.Form(FormEntry.Blank),
                TrainsScreen.PnrCheck(TICKET, pnr = "8524317690"),
                TrainsScreen.RouteFetch(TICKET, TRAIN),
                TrainsScreen.SeatMap(TICKET, TRAIN),
                TrainsScreen.RouteView(TICKET, TRAIN),
            )
        screens.forEach { screen ->
            assertThat(trainsBackTarget(screen, detailTicketId = null)).isNotNull()
        }
    }

    // --- routeFetchLanding: where an APPLIED route fetch ends up ---

    @Test
    fun `explicit route fetch from the list lands on the offline route page`() {
        assertThat(routeFetchLanding(TrainsScreen.RouteFetch(TICKET, TRAIN)))
            .isEqualTo(TrainsScreen.RouteView(TICKET, TRAIN))
    }

    @Test
    fun `chained route fetch closes to the list`() {
        assertThat(routeFetchLanding(TrainsScreen.RouteFetch(TICKET, TRAIN, chained = true)))
            .isEqualTo(TrainsScreen.List)
    }

    @Test
    fun `route fetch opened from the seat map returns to that seat map`() {
        val seatMap = TrainsScreen.SeatMap(TICKET, TRAIN, fromDetail = true)
        assertThat(routeFetchLanding(TrainsScreen.RouteFetch(TICKET, TRAIN, returnTo = seatMap))).isEqualTo(seatMap)
        // An opener always wins over the chained flag (it cannot be chained anyway).
        assertThat(routeFetchLanding(TrainsScreen.RouteFetch(TICKET, TRAIN, returnTo = seatMap, chained = true)))
            .isEqualTo(seatMap)
    }

    @Test
    fun `route page refresh returns to that route page`() {
        val routeView = TrainsScreen.RouteView(TICKET, TRAIN, fromDetail = true)
        assertThat(routeFetchLanding(TrainsScreen.RouteFetch(TICKET, TRAIN, returnTo = routeView))).isEqualTo(routeView)
    }

    @Test
    fun `chained route fetch still backs out to the list`() {
        assertThat(trainsBackTarget(TrainsScreen.RouteFetch(TICKET, TRAIN, chained = true), detailTicketId = null))
            .isEqualTo(TrainsBackTarget(TrainsScreen.List))
    }

    private companion object {
        const val TICKET = "ticket-1"
        const val TRAIN = "22346"
    }
}
