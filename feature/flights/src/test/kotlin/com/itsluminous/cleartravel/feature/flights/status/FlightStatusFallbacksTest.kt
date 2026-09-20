package com.itsluminous.cleartravel.feature.flights.status

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class FlightStatusFallbacksTest {
    @Test
    fun `web search url encodes the airline name and flight`() {
        val url = FlightStatusFallbacks.webSearchUrl("SG", "8194", airlineName = "SpiceJet")

        assertThat(url).startsWith("https://www.google.com/search?q=")
        assertThat(url).contains("SpiceJet")
        assertThat(url).contains("8194")
        assertThat(url).doesNotContain(" ")
    }

    @Test
    fun `web search falls back to the IATA code without a name`() {
        val url = FlightStatusFallbacks.webSearchUrl("ZZ", "123")

        assertThat(url).contains("ZZ")
    }

    @Test
    fun `check-in search url mentions check-in`() {
        val url = FlightStatusFallbacks.checkInSearchUrl("SG", "SpiceJet")

        assertThat(url).contains("check-in")
        assertThat(url).contains("SpiceJet")
    }

    @Test
    fun `airindia dates format as yyyyMMdd`() {
        val formatted = FlightStatusFallbacks.formatDateForRule("airindia", LocalDate.parse("2026-09-20"))

        assertThat(formatted).isEqualTo("20260920")
    }

    @Test
    fun `unknown rules default to ISO dates`() {
        val formatted = FlightStatusFallbacks.formatDateForRule("someairline", LocalDate.parse("2026-09-20"))

        assertThat(formatted).isEqualTo("2026-09-20")
    }
}
