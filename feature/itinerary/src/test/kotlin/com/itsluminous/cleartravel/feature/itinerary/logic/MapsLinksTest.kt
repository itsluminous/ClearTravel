package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ADR-029 part D: Google Maps share links → classifier, short-link detection, place parsing. */
class MapsLinksTest {
    // ---- classifier ----

    @Test
    fun `recognises maps links wrapped in shared text`() {
        assertThat(MapsLinks.isMapsLink("Check out Hampi Bazaar\nhttps://maps.app.goo.gl/AbCdEf123")).isTrue()
        assertThat(MapsLinks.isMapsLink("https://www.google.com/maps/place/Gateway+of+India/@18.92,72.83,17z")).isTrue()
        assertThat(MapsLinks.isMapsLink("https://maps.google.com/?q=12.97,77.59")).isTrue()
        assertThat(MapsLinks.isMapsLink("https://goo.gl/maps/xyz")).isTrue()
        assertThat(MapsLinks.isMapsLink("https://google.co.in/maps/search/coffee")).isTrue()
    }

    @Test
    fun `rejects non-maps text so it keeps routing to the train SMS form`() {
        assertThat(MapsLinks.isMapsLink("PNR:1234567890,TRN:12627,DOJ:20-10-26, SBC-NDLS")).isFalse()
        assertThat(MapsLinks.isMapsLink("https://www.google.com/search?q=maps")).isFalse()
        assertThat(MapsLinks.isMapsLink("https://goo.gl/abc")).isFalse()
        assertThat(MapsLinks.isMapsLink("https://example.com/maps/place/Fake")).isFalse()
        assertThat(MapsLinks.isMapsLink("")).isFalse()
    }

    @Test
    fun `extractUrl returns the maps link and trims trailing punctuation`() {
        assertThat(MapsLinks.extractUrl("See https://maps.app.goo.gl/AbC. Then https://example.com"))
            .isEqualTo("https://maps.app.goo.gl/AbC")
        assertThat(MapsLinks.extractUrl("no link here")).isNull()
    }

    @Test
    fun `short links are flagged for resolution and full links are not`() {
        assertThat(MapsLinks.isShortLink("https://maps.app.goo.gl/AbC")).isTrue()
        assertThat(MapsLinks.isShortLink("https://goo.gl/maps/AbC")).isTrue()
        assertThat(MapsLinks.isShortLink("https://www.google.com/maps/place/X/@1,2,3z")).isFalse()
    }

    // ---- parser ----

    @Test
    fun `place URL yields decoded name and the precise pin over the viewport centre`() {
        val place =
            MapsLinks.parse(
                "https://www.google.com/maps/place/Gateway+of+India/@18.9219841,72.8320,17z/" +
                    "data=!3m1!4b1!4m6!3m5!1s0x3be7d1c73a0d5cad:0xc70a25a7209c733c!8m2!3d18.921984!4d72.834654!16zL20vMDF0Yzg5",
            )

        assertThat(place.name).isEqualTo("Gateway of India")
        assertThat(place.latitude).isEqualTo(18.921984)
        assertThat(place.longitude).isEqualTo(72.834654)
    }

    @Test
    fun `place name is percent-decoded`() {
        val place = MapsLinks.parse("https://www.google.com/maps/place/Caf%C3%A9+de+Flore%2C+Paris/@48.854,2.3325,17z")

        assertThat(place.name).isEqualTo("Café de Flore, Paris")
        assertThat(place.latitude).isEqualTo(48.854)
        assertThat(place.longitude).isEqualTo(2.3325)
    }

    @Test
    fun `at-coordinates without a place segment give a nameless pin`() {
        val place = MapsLinks.parse("https://www.google.com/maps/@12.9716,77.5946,15z")

        assertThat(place.name).isNull()
        assertThat(place.hasLocation).isTrue()
        assertThat(place.latitude).isEqualTo(12.9716)
        assertThat(place.longitude).isEqualTo(77.5946)
    }

    @Test
    fun `q parameter with coordinates is a location, with text it is a name`() {
        val pin = MapsLinks.parse("https://maps.google.com/?q=-33.8688,151.2093")
        assertThat(pin.latitude).isEqualTo(-33.8688)
        assertThat(pin.longitude).isEqualTo(151.2093)
        assertThat(pin.name).isNull()

        val named = MapsLinks.parse("https://www.google.com/maps/search/?api=1&query=Taj+Mahal%2C+Agra")
        assertThat(named.name).isEqualTo("Taj Mahal, Agra")
        assertThat(named.hasLocation).isFalse()

        val encodedComma = MapsLinks.parse("https://maps.google.com/?q=loc:12.5%2C77.25")
        assertThat(encodedComma.latitude).isEqualTo(12.5)
        assertThat(encodedComma.longitude).isEqualTo(77.25)
    }

    @Test
    fun `ll and destination parameters are accepted`() {
        assertThat(MapsLinks.parse("https://maps.google.com/maps?ll=51.5007,-0.1246&z=15").longitude).isEqualTo(-0.1246)
        val directions = MapsLinks.parse("https://www.google.com/maps/dir/?api=1&destination=27.1751,78.0421")
        assertThat(directions.latitude).isEqualTo(27.1751)
    }

    @Test
    fun `out-of-range coordinates are ignored and a bare short link has nothing`() {
        assertThat(MapsLinks.parse("https://maps.google.com/?q=95.0,200.0").hasLocation).isFalse()
        val short = MapsLinks.parse("https://maps.app.goo.gl/AbC")
        assertThat(short.name).isNull()
        assertThat(short.hasLocation).isFalse()
        assertThat(short.url).isEqualTo("https://maps.app.goo.gl/AbC")
    }
}
