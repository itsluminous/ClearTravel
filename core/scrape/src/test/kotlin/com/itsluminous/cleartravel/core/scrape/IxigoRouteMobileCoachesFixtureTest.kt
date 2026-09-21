package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Third fixture for the `ixigo-route` rule (ADR-022 addendum): the LIVE MOBILE
 * layout the in-app WebView actually renders for train 22346, captured through
 * DevTools on the Android 16 emulator during on-device validation (2026-09-21).
 *
 * It exists because the coach strip's markup is NOT layout-independent as ADR-022
 * first assumed: the desktop recon (`docs/recon/ixigo-22346.html`, spliced into
 * `page.html`) nests `.coach-position-container > .coach-box-container > .coach-box`
 * with the coach CODE as the box text, whereas the mobile page nests
 * `.coach-boxes > .coach-box-cntr > (.coach-number, .coach-box)` where `.coach-box`
 * carries the coach TYPE (`CC`, `EC`) and the code sits in the sibling
 * `.coach-number`. Rule v3 therefore extracted ZERO coaches live; v4 selects both
 * row shapes and prefers `.coach-number` over `.coach-box` per row. `page.html`
 * (desktop shape) and `multiday.html` stay green, so both layouts are pinned.
 */
class IxigoRouteMobileCoachesFixtureTest {
    private fun extract(): ScrapedData {
        val rule = RuleFixtureHarness.loadRule("ixigo-route.json")
        val html =
            requireNotNull(RuleFixtureHarness.fixtureText("ixigo-route", "mobile.html")) {
                "Missing fixture mobile.html for ixigo-route"
            }
        val result = RuleExtractor.extract(rule, html)
        assertWithMessage("Extraction failed: ${(result as? ExtractionResult.Failure)?.reason}")
            .that(result)
            .isInstanceOf(ExtractionResult.Success::class.java)
        return (result as ExtractionResult.Success).data
    }

    @Test
    fun `mobile coach strip yields coach codes not coach types`() {
        val coaches = extract().extraRows.getValue("coaches").map { it.getValue("code") }

        assertThat(coaches)
            .containsExactly("EN", "C1", "C2", "C3", "C4", "C5", "E1", "C6", "C7")
            .inOrder()
        // The trap v3 fell into: `.coach-box` text on mobile is the TYPE column.
        assertThat(coaches).containsNoneOf("CC", "EC", "En")
    }

    @Test
    fun `route rows and header still extract from the mobile page`() {
        val data = extract()

        assertThat(data.fields["trainNumber"]).isEqualTo("22346")
        assertThat(data.fields["trainName"]).isEqualTo("Vande Bharat Exp")
        assertThat(data.rows).hasSize(7)
        assertThat(data.rows.first()["stationCode"]).isEqualTo("gtnr")
        assertThat(data.rows.last()["stationCode"]).isEqualTo("pnbe")
    }
}
