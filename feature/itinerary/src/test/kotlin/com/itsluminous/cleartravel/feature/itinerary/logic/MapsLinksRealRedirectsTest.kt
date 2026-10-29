package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * ADR-048 root-cause fixture: the user's five `maps.app.goo.gl` links, followed with
 * curl on 2026-10-28 (Java and Android-Chrome user agents gave the SAME `Location`).
 * Every one lands on `/maps/place/<full address>/data=!4m2!3m1!1s<ftid>!18m1!1e1` —
 * a place-id-only URL with NO coordinates in any form the parser knows (`!3d!4d`,
 * `@lat,lng`, `q=`…). The parser therefore yields the NAME and nothing else, which is
 * exactly what the user's trip contained. The landing page's HTML is no help either:
 * its only coordinates are the requester's IP location (`rumi-darwaza-landing-snippet`).
 */
class MapsLinksRealRedirectsTest {
    private val redirects: List<Pair<String, String>> =
        checkNotNull(javaClass.getResourceAsStream("/maps-links/real-redirects.txt"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.substringBefore('|') to line.substringAfter('|') }

    @Test
    fun `fixture holds the five user links as short links`() {
        assertThat(redirects).hasSize(5)
        redirects.forEach { (short, _) ->
            assertThat(MapsLinks.isShortLink(short)).isTrue()
            assertThat(MapsLinks.parse(short).hasLocation).isFalse()
        }
    }

    @Test
    fun `every real redirect is a place-id URL - name parsed, no coordinates`() {
        val names = redirects.map { (_, location) -> MapsLinks.parse(location) }
        names.forEach { place ->
            assertThat(place.hasLocation).isFalse()
            assertThat(place.name).isNotEmpty()
        }
        assertThat(names.map { it.name })
            .containsExactly(
                "Bada Imambara (Main Building), Machchhi Bhavan, Lucknow, Uttar Pradesh 226003",
                "Chota Imambada, 447, Husainabad, Lucknow, Uttar Pradesh 226003",
                "Royal Cafe(Hazratganj), 51, Mahatma Gandhi Marg, Sushanpura, Hazratganj, Lucknow, Uttar Pradesh 226001",
                "VW6G+7P3 The Residency, Lucknow, Mahatma Gandhi Marg, Deep Manak Nagar, Kaiser Bagh, Lucknow, Uttar Pradesh 226018",
                "Rumi Darwaza, 17/11 Hussainabad Road, Lajpat Nagar Colony, Lajpat Nagar, Machchhi Bhavan, Lucknow, Uttar Pradesh 226003",
            ).inOrder()
        redirects.forEach { (_, location) ->
            assertThat(location).contains("/data=!4m2!3m1!1s0x")
            assertThat(location).doesNotContainMatch("""!3d-?\d|@-?\d+\.\d+,""")
        }
    }

    /** Documents WHY the ladder does not read the landing HTML: its coordinates are the requester's, not the place's. */
    @Test
    fun `landing page HTML only carries the requester location`() {
        val html = checkNotNull(javaClass.getResourceAsStream("/maps-links/rumi-darwaza-landing-snippet.html")).bufferedReader().readText()
        assertThat(html).contains("APP_INITIALIZATION_STATE=[[[498015.9764601904,77.6503865,12.7947375]")
        assertThat(html).contains("staticmap?center=12.7947375%2C77.6503865")
        // Rumi Darwaza is at ~26.87 N, 80.91 E — nowhere in the page.
        assertThat(html).doesNotContainMatch("""26\.8\d+""")
        assertThat(html).doesNotContainMatch("""80\.9\d+""")
    }

    /** The coordinate forms the parser DOES read, so a link that carries them still wins rung 1. */
    @Test
    fun `coordinate-bearing google maps URLs still parse`() {
        val pin =
            MapsLinks.parse(
                "https://www.google.com/maps/place/Rumi+Darwaza/@26.8712,80.9122,17z/data=!3m1!4b1!4m6!3m5!1s0x399bfdf0522e588b:0xb2370b12db96ece3!8m2!3d26.8712234!4d80.9122124!16s",
            )
        assertThat(pin.latitude).isWithin(1e-9).of(26.8712234)
        assertThat(pin.longitude).isWithin(1e-9).of(80.9122124)
        assertThat(MapsLinks.parse("https://maps.google.com/?q=26.8712,80.9122").hasLocation).isTrue()
        assertThat(MapsLinks.parse("https://www.google.com/maps/search/?api=1&query=26.8712%2C80.9122").hasLocation).isTrue()
        assertThat(MapsLinks.parse("https://maps.google.com/maps?ll=26.8712,80.9122&z=15").hasLocation).isTrue()
        assertThat(MapsLinks.parse("https://maps.google.com/maps?cid=12841745038091676899").hasLocation).isFalse()
        assertThat(MapsLinks.parse("https://maps.google.com/?ftid=0x399bfdf0522e588b:0xb2370b12db96ece3").hasLocation).isFalse()
    }
}
