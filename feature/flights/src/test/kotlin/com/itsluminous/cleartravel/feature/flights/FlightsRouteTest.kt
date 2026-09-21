package com.itsluminous.cleartravel.feature.flights

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The one-step-back map behind the Flights segment's system-back handling. */
class FlightsRouteTest {
    @Test
    fun `journeys list does not handle back`() {
        assertThat(flightsBackRoute(FlightsRoute.Journeys)).isNull()
    }

    @Test
    fun `status check leaves back to the screen so the outcome travels`() {
        assertThat(flightsBackRoute(FlightsRoute.StatusCheck("flight-1"))).isNull()
    }

    @Test
    fun `form closes to the list without writing`() {
        assertThat(flightsBackRoute(FlightsRoute.Form())).isEqualTo(FlightsRoute.Journeys)
        assertThat(flightsBackRoute(FlightsRoute.Form(editId = "flight-1"))).isEqualTo(FlightsRoute.Journeys)
        assertThat(flightsBackRoute(FlightsRoute.Form(importUri = "content://pass"))).isEqualTo(FlightsRoute.Journeys)
    }

    @Test
    fun `document viewer closes to the list`() {
        assertThat(flightsBackRoute(FlightsRoute.PassViewer("/pass.png"))).isEqualTo(FlightsRoute.Journeys)
        assertThat(flightsBackRoute(FlightsRoute.PassViewer("/booking.pdf", titleRes = R.string.flights_booking_viewer_title)))
            .isEqualTo(FlightsRoute.Journeys)
    }
}
