package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Second, fixture-only test for the `ixigo-route` rule beyond the harness' one
 * fixture pair (ADR-019): train 13151 (KOAA→JAT) spans THREE running days across
 * two midnight crossings — multi-day correctness is a user requirement.
 *
 * The fixture is the LIVE MOBILE layout ixigo serves to the in-app WebView
 * (captured on-device 2026-09-21, rule v2): `table.train-route-cntr`, five columns
 * (Station · Arrives · Depart · Halt · PF), **no Day column and no station-code
 * cell** — the code lives only in the station link's href slug, and the running
 * day is INFERRED by `RouteMapper`'s midnight-crossing rule. This test pins the
 * extraction shape plus the exact inference preconditions: the only two arrival
 * regressions in the whole capture sit at DDU (01:25) and YJUD (00:06), so the
 * mapper's day counter yields 1→2→3 switching at exactly those stations.
 * The harness contract supports exactly one `page.html` per rule, hence this plain
 * unit test feeding the extra `multiday.html` fixture through the same pure
 * [RuleExtractor] path.
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
    fun `all 85 stations extract with codes recovered from the href slug`() {
        val data = extract()

        assertThat(data.rows).hasSize(85)
        assertThat(data.rows.first()["stationCode"]).isEqualTo("koaa")
        assertThat(data.rows.last()["stationCode"]).isEqualTo("jat")
        assertThat(data.rows.first()["stationName"]).isEqualTo("Kolkata Chitpur")
        assertThat(data.rows.last()["stationName"]).isEqualTo("Jammu Tawi")
    }

    @Test
    fun `coach composition extracts as the coaches extraRows set in rake order`() {
        val coaches = extract().extraRows.getValue("coaches").map { it.getValue("code") }

        // Real 13151 capture (docs/recon/ixigo-13151.html): 20 coaches, engine first,
        // mixed GN/SL/pantry/3A/2A with duplicated GN codes at both ends (ADR-022).
        assertThat(coaches)
            .containsExactly(
                "EN",
                "GN",
                "GN",
                "S1",
                "S2",
                "S3",
                "S4",
                "S5",
                "S6",
                "S7",
                "PC",
                "M1",
                "B1",
                "B2",
                "B3",
                "B4",
                "B5",
                "A1",
                "GN",
                "GN",
            ).inOrder()
    }

    @Test
    fun `train header fields parse from the plain name-then-number h1`() {
        val data = extract()

        assertThat(data.fields["trainNumber"]).isEqualTo("13151")
        assertThat(data.fields["trainName"]).isEqualTo("Koaa Jat Expres")
    }

    @Test
    fun `mobile layout has no day column and exactly two midnight crossings for the mapper to infer`() {
        val rows = extract().rows

        // No day key at all — day is RouteMapper's inference job on this layout.
        assertThat(rows.none { it.containsKey("day") }).isTrue()

        // The mapper's inference rule: a stop whose reference time (arrival,
        // else departure) regresses past the previous stop's starts a new day.
        // Pin that this capture contains EXACTLY two regressions, at DDU and YJUD.
        val timePattern = Regex("^([0-9]{1,2}):([0-9]{2})$")
        var previous: Int? = null
        val crossings = mutableListOf<String>()
        for (row in rows) {
            val raw = row["arrival"].orEmpty().ifEmpty { row["departure"].orEmpty() }
            val match = timePattern.find(raw) ?: continue
            val minutes = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
            if (previous != null && minutes < previous!!) crossings += row["stationCode"].orEmpty()
            previous = minutes
        }
        assertThat(crossings).containsExactly("ddu", "yjud").inOrder()
    }

    @Test
    fun `unit-suffixed cells and placeholder literals arrive raw for the mapper`() {
        val rows = extract().rows

        val origin = rows.first()
        assertThat(origin["arrival"]).isEqualTo("Starts")
        assertThat(origin["halt"]).isEqualTo("-")
        assertThat(origin["distance"]).isEmpty()
        val terminus = rows.last()
        assertThat(terminus["departure"]).isEqualTo("Ends")
        // Mid-route rows carry the km / min suffixes the mapper ignores/strips,
        // and the platform placeholder appears on stations without data.
        assertThat(rows[1]["distance"]).endsWith(" km")
        assertThat(rows[1]["halt"]).endsWith("min")
        assertThat(rows.count { it["platform"] == "-" }).isEqualTo(3)
    }
}
