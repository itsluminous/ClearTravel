package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Second, fixture-only test for the `ixigo-route` rule beyond the harness' one
 * fixture pair (ADR-019): train 13151 (KOAA→JAT) spans THREE running days across
 * two midnight crossings — multi-day correctness is a user requirement, so the day
 * column's cumulative progression is pinned against the real capture
 * (`docs/recon/ixigo-13151.html`, recon 2026-09-21). The harness contract supports
 * exactly one `page.html` per rule, hence this plain unit test feeding the extra
 * `multiday.html` fixture through the same pure [RuleExtractor] path.
 */
class IxigoRouteMultiDayFixtureTest {
    private fun extract(): ScrapedData {
        val rule = RuleFixtureHarness.loadRule("ixigo-route.json")
        val html =
            requireNotNull(RuleFixtureHarness.fixtureText("ixigo-route", "multiday.html")) {
                "Missing fixture multiday.html for ixigo-route"
            }
        val result = RuleExtractor.extract(rule, html)
        assertWithMessage("Extraction failed: ${(result as? ExtractionResult.Failure)?.reason}")
            .that(result)
            .isInstanceOf(ExtractionResult.Success::class.java)
        return (result as ExtractionResult.Success).data
    }

    @Test
    fun `all 85 stations extract despite the tbody div-wrapping quirk`() {
        val data = extract()

        assertThat(data.rows).hasSize(85)
        assertThat(data.rows.first()["stationCode"]).isEqualTo("KOAA")
        assertThat(data.rows.last()["stationCode"]).isEqualTo("JAT")
    }

    @Test
    fun `train header fields parse from the name-then-number h1`() {
        val data = extract()

        assertThat(data.fields["trainNumber"]).isEqualTo("13151")
        assertThat(data.fields["trainName"]).isEqualTo("Koaa Jat Expres")
    }

    @Test
    fun `day column progresses 1 to 3 switching exactly at the midnight crossings`() {
        val rows = extract().rows

        val days = rows.map { it["day"]?.toIntOrNull() }
        assertThat(days).doesNotContain(null)
        // Cumulative, never decreasing, covering all three running days.
        assertThat(days.distinct()).containsExactly(1, 2, 3).inOrder()
        assertThat(days.zipWithNext().all { (a, b) -> b!! >= a!! }).isTrue()
        // The two midnight-crossing stations pin the exact switch points.
        assertThat(rows.first { it["day"] == "2" }["stationCode"]).isEqualTo("DDU")
        assertThat(rows.first { it["day"] == "3" }["stationCode"]).isEqualTo("YJUD")
    }

    @Test
    fun `unit-suffixed cells and placeholder literals arrive raw for the mapper`() {
        val rows = extract().rows

        val origin = rows.first()
        assertThat(origin["arrival"]).isEqualTo("starts")
        assertThat(origin["halt"]).isEqualTo("-")
        assertThat(origin["distance"]).isEqualTo("0")
        val terminus = rows.last()
        assertThat(terminus["departure"]).isEqualTo("ends")
        // Mid-route rows carry the km / min suffixes the mapper strips.
        assertThat(rows[1]["distance"]).endsWith(" km")
        assertThat(rows[1]["halt"]).endsWith("min")
    }
}
