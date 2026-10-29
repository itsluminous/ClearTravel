package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import org.junit.Test

/**
 * [FlightDocumentPages] (ADR-045 amended): the page-level triage that keeps an airline
 * e-ticket's conditions-of-carriage pages — prose quoting example flights (`6E-333`),
 * terminals and city pairs — out of the segmenter, while every page that carries flight
 * data (a date or a labeled locator) is kept however dense its fine print.
 */
class FlightDocumentPagesTest {
    private val pages = FixtureLoader.read("indigo-return-two-pax-pdf.txt").split(FlightDocumentPages.PAGE_BREAK)

    @Test
    fun `the real nine-page print keeps its five data pages and drops the four prose pages`() {
        assertThat(pages).hasSize(9)
        val verdicts = pages.map { FlightDocumentPages.isProsePage(it) }
        // 1-4: itinerary page pairs; 5: baggage + fare; 6-9: notes, T&C, terminal info, delays.
        assertThat(verdicts).containsExactly(false, false, false, false, false, true, true, true, true).inOrder()

        val kept = FlightDocumentPages.withoutProsePages(pages.joinToString(FlightDocumentPages.PAGE_BREAK))
        assertThat(kept.split(FlightDocumentPages.PAGE_BREAK)).hasSize(5)
        assertThat(kept).doesNotContain("6E-333")
        assertThat(kept).contains("6E 432")
    }

    @Test
    fun `a short itinerary page or a boarding pass is never prose`() {
        assertThat(FlightDocumentPages.isProsePage(pages[0])).isFalse()
        assertThat(FlightDocumentPages.isProsePage(FixtureLoader.read("boarding-pass-1.txt"))).isFalse()
        assertThat(FlightDocumentPages.isProsePage("")).isFalse()
    }

    @Test
    fun `dense fine print that still carries a date or a locator is kept`() {
        val prose = pages[6] // power banks + fog advisory: pure prose
        assertThat(FlightDocumentPages.isProsePage(prose)).isTrue()
        assertThat(FlightDocumentPages.isProsePage(prose + "\nTravel Date: 10 Nov 2026")).isFalse()
        assertThat(FlightDocumentPages.isProsePage(prose + "\nPNR: OC9ISJ")).isFalse()
    }

    @Test
    fun `a single-page document is returned untouched`() {
        val single = pages[6]
        assertThat(FlightDocumentPages.withoutProsePages(single)).isEqualTo(single)
    }
}
