package com.itsluminous.cleartravel.ui

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddRequest
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddResult
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.feature.flights.FlightsEntryResult
import com.itsluminous.cleartravel.feature.trains.TrainsEntryResult
import org.junit.Test

/**
 * ADR-028: every way the Journeys segment can finish a pick maps onto exactly one bus
 * result carrying the requester's nonce — saves and refused duplicates both link a
 * journey, cancels link nothing.
 */
class JourneyPickRoutingTest {
    private val trainRequest = JourneyAddRequest(JourneyType.TRAIN, nonce = 42L)
    private val flightRequest = JourneyAddRequest(JourneyType.FLIGHT, nonce = 7L)

    @Test
    fun `saved train ticket becomes Added with the request nonce`() {
        val result = JourneyPickRouting.resultFor(trainRequest, TrainsEntryResult.Saved("t1", openPnrCheck = true))

        assertThat(result).isEqualTo(JourneyAddResult.Added(42L, JourneyType.TRAIN, "t1"))
    }

    @Test
    fun `refused duplicate PNR links the EXISTING ticket`() {
        val result = JourneyPickRouting.resultFor(trainRequest, TrainsEntryResult.DuplicatePnr("existing"))

        assertThat(result).isEqualTo(JourneyAddResult.Added(42L, JourneyType.TRAIN, "existing"))
    }

    @Test
    fun `cancelled train add becomes Cancelled`() {
        assertThat(JourneyPickRouting.resultFor(trainRequest, TrainsEntryResult.Cancelled))
            .isEqualTo(JourneyAddResult.Cancelled(42L))
    }

    @Test
    fun `saved flight becomes Added with the request nonce`() {
        val result = JourneyPickRouting.resultFor(flightRequest, FlightsEntryResult.Saved("f1"))

        assertThat(result).isEqualTo(JourneyAddResult.Added(7L, JourneyType.FLIGHT, "f1"))
    }

    @Test
    fun `refused duplicate flight links the EXISTING journey`() {
        val result = JourneyPickRouting.resultFor(flightRequest, FlightsEntryResult.DuplicateFlight("existing"))

        assertThat(result).isEqualTo(JourneyAddResult.Added(7L, JourneyType.FLIGHT, "existing"))
    }

    @Test
    fun `cancelled flight add becomes Cancelled`() {
        assertThat(JourneyPickRouting.resultFor(flightRequest, FlightsEntryResult.Cancelled))
            .isEqualTo(JourneyAddResult.Cancelled(7L))
    }
}
