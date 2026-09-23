package com.itsluminous.cleartravel.feature.flights.share

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.share.FlightSharePayload
import com.itsluminous.cleartravel.core.data.share.ShareDecodeResult
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.flights.form.FlightFormState
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** ADR-039 part A: the flight share caption and the link's round trip into the add form. */
class FlightShareTextTest {
    private val template = "My flight %1\$s - open in Clear Travel to add it: %2\$s"
    private val flight =
        Fixtures.flightJourney(
            airlineIata = "AI",
            flightNumber = "101",
            date = LocalDate.parse("2026-09-24"),
            depAirport = "BLR",
            arrAirport = "DEL",
        )

    @Test
    fun `headline joins number, date and route`() {
        assertThat(FlightShareText.headline(flight, "→")).isEqualTo("AI 101 · 24 Sep · BLR → DEL")
    }

    @Test
    fun `headline omits unknown date and route`() {
        val bare = flight.copy(date = null, depAirport = "", arrAirport = "")

        assertThat(FlightShareText.headline(bare, "→")).isEqualTo("AI 101")
        assertThat(FlightShareText.headline(flight.copy(arrAirport = ""), "→")).isEqualTo("AI 101 · 24 Sep · BLR")
    }

    @Test
    fun `share text fills the template with headline and a flight share link`() {
        val url = (FlightShareText.shareUrl(flight) as ShareUrlResult.Ok).url
        val text = FlightShareText.buildShareText(flight, url, template, "→")

        assertThat(text).isEqualTo("My flight AI 101 · 24 Sep · BLR → DEL - open in Clear Travel to add it: $url")
        assertThat(url).startsWith("https://cleartravel.itsluminous.com/share/flight/")
        assertThat(url.length).isLessThan(400)
    }

    @Test
    fun `shared link prefills the add form with identity, route and device-zone times`() {
        val url = (FlightShareText.shareUrl(flight) as ShareUrlResult.Ok).url
        val payload = (ShareLinkCodec.decode(url) as ShareDecodeResult.Ok).payload as FlightSharePayload

        val state = FlightFormState.fromSharePayload(payload, zone = ZoneOffset.UTC)

        assertThat(state.airlineIata).isEqualTo("AI")
        assertThat(state.flightNumber).isEqualTo("101")
        assertThat(state.dateText).isEqualTo("2026-09-24")
        assertThat(state.depAirport).isEqualTo("BLR")
        assertThat(state.arrAirport).isEqualTo("DEL")
        // Fixtures.NOW is 09:00Z; arrival +3 h.
        assertThat(state.depTimeText).isEqualTo("9:00")
        assertThat(state.arrTimeText).isEqualTo("12:00")
        assertThat(state.pnr).isEmpty()
        assertThat(state.seat).isEmpty()
        assertThat(state.fromSharedLink).isTrue()
        assertThat(FlightFormState.validate(state)).isEmpty()
    }
}
