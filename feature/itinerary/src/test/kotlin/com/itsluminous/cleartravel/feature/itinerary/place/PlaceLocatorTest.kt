package com.itsluminous.cleartravel.feature.itinerary.place

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.feature.itinerary.fakes.FakePlaceGeocoder
import com.itsluminous.cleartravel.feature.itinerary.fakes.VirtualPacer
import com.itsluminous.cleartravel.feature.itinerary.intake.MapsLinkResolver
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** ADR-048: the ladder — link coordinates → short-link expansion → geocode candidates → plus code → null. */
class PlaceLocatorTest {
    private val geocoder = FakePlaceGeocoder()
    private val virtual = VirtualPacer()
    private val resolver =
        object : MapsLinkResolver {
            val expansions = mutableMapOf<String, String?>()
            val calls = mutableListOf<String>()

            override suspend fun resolve(shortUrl: String): String? {
                calls += shortUrl
                return expansions[shortUrl]
            }
        }
    private val locator = DefaultPlaceLocator(resolver, geocoder, virtual.pacer)

    @Test
    fun `a link with coordinates wins without any geocoding`() =
        runTest {
            val located = locator.locate("https://maps.google.com/?q=26.8712,80.9122", "Rumi Darwaza, Lucknow")

            assertThat(located?.source).isEqualTo(PlaceSource.LINK)
            assertThat(located?.point?.latitude).isEqualTo(26.8712)
            assertThat(geocoder.queries).isEmpty()
            assertThat(resolver.calls).isEmpty()
        }

    @Test
    fun `a short link is expanded and its coordinates taken when the redirect has them`() =
        runTest {
            resolver.expansions[SHORT] = "https://www.google.com/maps/place/Rumi+Darwaza/@26.87,80.91,17z/data=!3d26.8712234!4d80.9122124"

            val located = locator.locate(SHORT, null)

            assertThat(located?.source).isEqualTo(PlaceSource.LINK)
            assertThat(located?.linkName).isEqualTo("Rumi Darwaza")
            assertThat(located?.point?.longitude).isEqualTo(80.9122124)
        }

    /** The user's real case: a place-id redirect names the place; the geocode rungs climb until one answers. */
    @Test
    fun `a place-id redirect falls through to the geocode ladder in order`() =
        runTest {
            resolver.expansions[SHORT] = RUMI_REDIRECT
            geocoder.answer("Rumi Darwaza, Lucknow", 26.8712234, 80.9122124, label = "Rumi Gate, Azadari Road, Chowk, Lucknow")

            val located = locator.locate(SHORT, null)

            assertThat(located?.source).isEqualTo(PlaceSource.GEOCODE)
            assertThat(located?.point?.latitude).isEqualTo(26.8712234)
            assertThat(located?.linkName).startsWith("Rumi Darwaza, 17/11 Hussainabad Road")
            assertThat(geocoder.queries)
                .containsExactly(
                    "Rumi Darwaza, 17/11 Hussainabad Road, Lajpat Nagar Colony, Lajpat Nagar, Machchhi Bhavan, Lucknow, Uttar Pradesh 226003",
                    "Rumi Darwaza, Lucknow, Uttar Pradesh",
                    "Rumi Darwaza, Lucknow",
                ).inOrder()
        }

    @Test
    fun `the item's own text outranks the link name and the full text is tried first`() =
        runTest {
            resolver.expansions[SHORT] = RUMI_REDIRECT
            geocoder.answer("Chota Imambada, 447, Husainabad, Lucknow, Uttar Pradesh 226003", 26.87, 80.90)

            val located = locator.locate(SHORT, "Chota Imambada, 447, Husainabad, Lucknow, Uttar Pradesh 226003")

            assertThat(located?.source).isEqualTo(PlaceSource.GEOCODE)
            assertThat(geocoder.queries).hasSize(1)
        }

    @Test
    fun `a plus code is anchored to the geocoded locality when the name finds nothing`() =
        runTest {
            geocoder.answer("Lucknow, Uttar Pradesh", 26.8381, 80.9346)

            val located =
                locator.locate(
                    null,
                    "VW6G+7P3 The Residency, Lucknow, Mahatma Gandhi Marg, Kaiser Bagh, Lucknow, Uttar Pradesh 226018",
                )

            assertThat(located?.source).isEqualTo(PlaceSource.PLUS_CODE)
            assertThat(located?.point?.latitude).isWithin(1e-9).of(26.8606375)
            assertThat(located?.point?.longitude).isWithin(1e-9).of(80.926796875)
            assertThat(geocoder.queries.last()).isEqualTo("Lucknow, Uttar Pradesh")
        }

    @Test
    fun `a full plus code decodes offline`() =
        runTest {
            val located = locator.locate(null, "7MR2VW6G+7P3")

            assertThat(located?.source).isEqualTo(PlaceSource.PLUS_CODE)
            assertThat(located?.point?.latitude).isWithin(1e-9).of(26.8606375)
            assertThat(geocoder.queries).isEmpty()
        }

    @Test
    fun `nothing found anywhere yields null and a failing resolver is survived`() =
        runTest {
            val located = locator.locate(SHORT, "Nowhere, Atlantis")

            assertThat(located).isNull()
            assertThat(resolver.calls).containsExactly(SHORT)
            assertThat(geocoder.queries).containsExactly("Nowhere, Atlantis")
            assertThat(locator.locate(null, null)).isNull()
            assertThat(locator.locate("", "")).isNull()
        }

    @Test
    fun `geocoder calls are paced one second apart`() =
        runTest {
            locator.locate(null, "Rumi Darwaza, Lucknow, Uttar Pradesh 226003")

            assertThat(geocoder.queries).hasSize(3)
            assertThat(virtual.waits).containsExactly(1_000L, 1_000L)
        }

    private companion object {
        const val SHORT = "https://maps.app.goo.gl/SPre92rQxfbVNLoY7"
        const val RUMI_REDIRECT =
            "https://www.google.com/maps/place/Rumi+Darwaza,+17%2F11+Hussainabad+Road,+Lajpat+Nagar+Colony," +
                "+Lajpat+Nagar,+Machchhi+Bhavan,+Lucknow,+Uttar+Pradesh+226003/data=!4m2!3m1!1s0x399bfdf0522e588b:0xb2370b12db96ece3!18m1!1e1?utm_source=mstt_1&entry=gps"
    }
}
