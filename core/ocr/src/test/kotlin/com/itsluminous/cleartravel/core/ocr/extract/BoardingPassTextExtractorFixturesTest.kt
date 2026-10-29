package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fixture harness for the boarding-pass OCR-text fallback: every recorded fixture is
 * extracted and compared against its expected-output JSON — one field table per pass
 * (ADR-003 discipline; ADR-045 `boarding-pass-two-stacked` holds two passes on one page;
 * ADR-047 `boarding-pass-two-pax-same-flight` two passes for the SAME flight → one
 * segment with two passengers).
 */
@RunWith(Parameterized::class)
class BoardingPassTextExtractorFixturesTest(
    private val fixture: String,
) {
    private val extractor = BoardingPassTextExtractor()

    @Test
    fun extractsExpectedSegments() {
        val text = FixtureLoader.read("$fixture.txt")
        val expected =
            Json.decodeFromString<BoardingPassExtraction>(
                FixtureLoader.read("$fixture.expected.json"),
            )

        assertThat(extractor.extract(text)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf("boarding-pass-1", "boarding-pass-2", "boarding-pass-two-stacked", "boarding-pass-two-pax-same-flight")
    }
}

/** Non-parameterized companion cases: garbage fallback, structural never-throw, pass counting. */
class BoardingPassTextExtractorFallbackTest {
    private val extractor = BoardingPassTextExtractor()

    @Test
    fun `garbage fixture falls back to the empty result`() {
        val result = extractor.extract(FixtureLoader.read("garbage.txt"))
        assertThat(result).isEqualTo(BoardingPassExtraction.EMPTY)
        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun `empty and hostile input never throw`() {
        assertThat(extractor.extract("")).isEqualTo(BoardingPassExtraction.EMPTY)
        assertThat(extractor.extract("\u0000\uFFFD\n\t ~~~ ((( "))
            .isEqualTo(BoardingPassExtraction.EMPTY)
    }

    @Test
    fun `a single pass yields exactly one segment`() {
        assertThat(extractor.extract(FixtureLoader.read("boarding-pass-1.txt")).flights).hasSize(1)
        assertThat(extractor.extract(FixtureLoader.read("boarding-pass-2.txt")).flights).hasSize(1)
    }

    @Test
    fun `two passes stacked on one page yield two segments in page order`() {
        val result = extractor.extract(FixtureLoader.read("boarding-pass-two-stacked.txt"))

        assertThat(result.flights.map { it.flightNumber.value }).containsExactly("2345", "777").inOrder()
        assertThat(result.flights.map { it.seat.value }).containsExactly("12A", "3C").inOrder()
        assertThat(result.flights.map { it.flightDate.value }).containsExactly("2025-09-20", "2025-09-21").inOrder()
    }

    @Test
    fun `a booking confirmation handed to the pass importer still yields every leg and passenger`() {
        // ADR-045 amended: the IndiGo per-passenger print through the BOARDING_PASS profile.
        val result = extractor.extract(FixtureLoader.read("indigo-return-two-pax-pdf.txt"))

        assertThat(result.flights.map { it.flightNumber.value }).containsExactly("432", "6223").inOrder()
        assertThat(result.flights.map { it.flightDate.value }).containsExactly("2026-11-10", "2026-11-15").inOrder()
        for (flight in result.flights) {
            assertThat(flight.passengers.map { it.name.value }).containsExactly("HARI SHANKAR SINGH", "RAM NATH SINGH").inOrder()
            // No bare `6E` seat from the `Seat  6E Add-ons` header or the flight token.
            assertThat(flight.passengers.none { it.seat.isPresent }).isTrue()
        }
    }

    @Test
    fun `two pages for the same flight are one segment`() {
        val page = FixtureLoader.read("boarding-pass-1.txt")
        val result = extractor.extract(page + "\n\n" + page)

        assertThat(result.flights).hasSize(1)
    }
}
