package com.itsluminous.cleartravel.feature.itinerary.logic

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.testing.Fixtures
import org.junit.Test

/** ADR-048: geocode query ladder + plus-code mention + backfill eligibility, on the user's real place texts. */
class PlaceQueriesTest {
    @Test
    fun `candidates strip parentheticals and climb to name plus locality`() {
        assertThat(PlaceQueries.geocodeCandidates(BADA))
            .containsExactly(
                BADA,
                "Bada Imambara, Lucknow, Uttar Pradesh",
                "Bada Imambara, Lucknow",
            ).inOrder()
        assertThat(PlaceQueries.geocodeCandidates(ROYAL))
            .containsExactly(
                ROYAL,
                "Royal Cafe, Lucknow, Uttar Pradesh",
                "Royal Cafe, Lucknow",
            ).inOrder()
        assertThat(PlaceQueries.geocodeCandidates(CHOTA))
            .containsExactly(CHOTA, "Chota Imambada, Lucknow, Uttar Pradesh", "Chota Imambada, Lucknow")
            .inOrder()
    }

    @Test
    fun `a leading plus code is dropped from the name`() {
        assertThat(PlaceQueries.geocodeCandidates(RESIDENCY))
            .containsExactly(RESIDENCY, "The Residency, Lucknow, Uttar Pradesh", "The Residency, Lucknow")
            .inOrder()
    }

    @Test
    fun `a country tail and a US zip are peeled too`() {
        assertThat(PlaceQueries.geocodeCandidates("Empire State Building, 20 W 34th St., New York, NY 10001, USA"))
            .containsExactly(
                "Empire State Building, 20 W 34th St., New York, NY 10001, USA",
                "Empire State Building, New York, NY",
                "Empire State Building, New York",
            ).inOrder()
        assertThat(PlaceQueries.geocodeCandidates("Taj Mahal, Agra 282001"))
            .containsExactly("Taj Mahal, Agra 282001", "Taj Mahal, Agra")
            .inOrder()
    }

    @Test
    fun `a text without a comma is its own only candidate and never exceeds the cap`() {
        assertThat(PlaceQueries.geocodeCandidates("Rumi Darwaza")).containsExactly("Rumi Darwaza")
        assertThat(PlaceQueries.geocodeCandidates("   ")).isEmpty()
        assertThat(PlaceQueries.geocodeCandidates(RUMI).size).isAtMost(PlaceQueries.MAX_CANDIDATES)
    }

    @Test
    fun `plus code mention carries the code and the locality to anchor it`() {
        val mention = checkNotNull(PlaceQueries.plusCodeMention(RESIDENCY))
        assertThat(mention.code).isEqualTo("VW6G+7P3")
        assertThat(mention.locality).isEqualTo("Lucknow, Uttar Pradesh")
        assertThat(PlaceQueries.plusCodeMention(RUMI)).isNull()
        assertThat(PlaceQueries.plusCodeMention("VW6G+7P3")?.locality).isNull()
    }

    @Test
    fun `unplaced means a live place without coordinates that has a maps link or an address-like name`() {
        val base = Fixtures.itineraryItem(latitude = null, longitude = null, name = "Lunch")
        assertThat(PlaceQueries.isUnplaced(base)).isFalse()
        assertThat(PlaceQueries.isUnplaced(base.copy(link = "https://maps.app.goo.gl/SPre92rQxfbVNLoY7"))).isTrue()
        assertThat(PlaceQueries.isUnplaced(base.copy(name = RUMI))).isTrue()
        assertThat(PlaceQueries.isUnplaced(base.copy(name = RUMI, latitude = 26.8, longitude = 80.9))).isFalse()
        assertThat(PlaceQueries.isUnplaced(base.copy(name = RUMI, latitude = 26.8))).isTrue()
        assertThat(PlaceQueries.isUnplaced(base.copy(name = RUMI, deletedAt = Fixtures.NOW))).isFalse()
        assertThat(PlaceQueries.isUnplaced(base.copy(name = RUMI, type = ItineraryItemType.COMMUTE))).isFalse()
        assertThat(PlaceQueries.isUnplaced(base.copy(link = "https://example.com/not-maps"))).isFalse()
    }

    companion object {
        const val BADA = "Bada Imambara (Main Building), Machchhi Bhavan, Lucknow, Uttar Pradesh 226003"
        const val CHOTA = "Chota Imambada, 447, Husainabad, Lucknow, Uttar Pradesh 226003"
        const val ROYAL = "Royal Cafe(Hazratganj), 51, Mahatma Gandhi Marg, Sushanpura, Hazratganj, Lucknow, Uttar Pradesh 226001"
        const val RESIDENCY =
            "VW6G+7P3 The Residency, Lucknow, Mahatma Gandhi Marg, Deep Manak Nagar, Kaiser Bagh, Lucknow, Uttar Pradesh 226018"
        const val RUMI =
            "Rumi Darwaza, 17/11 Hussainabad Road, Lajpat Nagar Colony, Lajpat Nagar, Machchhi Bhavan, Lucknow, Uttar Pradesh 226003"
    }
}
