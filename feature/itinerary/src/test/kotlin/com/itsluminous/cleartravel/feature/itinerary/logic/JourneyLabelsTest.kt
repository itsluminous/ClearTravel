package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

class JourneyLabelsTest {
    @Test
    fun `train label is the train number`() {
        assertThat(trainJourneyLabel(Fixtures.trainTicket(trainNumber = " 12951 "))).isEqualTo("12951")
        assertThat(trainJourneyLabel(Fixtures.trainTicket(trainNumber = ""))).isNull()
    }

    @Test
    fun `flight label is airline plus number and tolerates a blank half`() {
        assertThat(flightJourneyLabel(Fixtures.flightJourney(airlineIata = "6E", flightNumber = "2001"))).isEqualTo("6E 2001")
        assertThat(flightJourneyLabel(Fixtures.flightJourney(airlineIata = "", flightNumber = "2001"))).isEqualTo("2001")
        assertThat(flightJourneyLabel(Fixtures.flightJourney(airlineIata = " ", flightNumber = ""))).isNull()
    }

    @Test
    fun `id fallback is the first eight characters`() {
        assertThat(journeyIdFallback("d72275b9-1234-5678")).isEqualTo("d72275b9")
        assertThat(journeyIdFallback("abc")).isEqualTo("abc")
    }
}
