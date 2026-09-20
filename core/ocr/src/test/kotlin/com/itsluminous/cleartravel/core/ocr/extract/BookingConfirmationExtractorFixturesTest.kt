package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fixture harness for the booking-confirmation OCR-text extractor: every recorded
 * fixture is extracted and compared against its expected-output JSON (ADR-003
 * discipline; ADR-017).
 */
@RunWith(Parameterized::class)
class BookingConfirmationExtractorFixturesTest(
    private val fixture: String,
) {
    private val extractor = BookingConfirmationExtractor()

    @Test
    fun extractsExpectedFields() {
        val text = FixtureLoader.read("$fixture.txt")
        val expected =
            Json.decodeFromString<BookingConfirmationExtraction>(
                FixtureLoader.read("$fixture.expected.json"),
            )

        assertThat(extractor.extract(text)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf("booking-airline-1", "booking-ota-1", "booking-return-1")
    }
}

/** Non-parameterized companion cases: fallbacks + multi-flight/OTA specifics. */
class BookingConfirmationExtractorBehaviorTest {
    private val extractor = BookingConfirmationExtractor()

    @Test
    fun `garbage fixture falls back to the empty result`() {
        val result = extractor.extract(FixtureLoader.read("garbage.txt"))
        assertThat(result).isEqualTo(BookingConfirmationExtraction.EMPTY)
        assertThat(result.isEmpty).isTrue()
        assertThat(result.additionalFlights).isEqualTo(0)
    }

    @Test
    fun `empty and hostile input never throw`() {
        assertThat(extractor.extract("")).isEqualTo(BookingConfirmationExtraction.EMPTY)
        assertThat(extractor.extract("\u0000\uFFFD\n\t ~~~ ((( "))
            .isEqualTo(BookingConfirmationExtraction.EMPTY)
    }

    @Test
    fun `return trip extracts the first leg fully and counts the second`() {
        val result =
            extractor.extract(
                """
                PNR: A1B2C3
                Onward 6E 2001 DEL - GOI Date: 10-01-2027
                Return 6E 2002 GOI - DEL Date: 17-01-2027
                """.trimIndent(),
            )

        assertThat(result.carrier.value).isEqualTo("6E")
        assertThat(result.flightNumber.value).isEqualTo("2001")
        assertThat(result.fromAirport.value).isEqualTo("DEL")
        assertThat(result.toAirport.value).isEqualTo("GOI")
        assertThat(result.flightDate.value).isEqualTo("2027-01-10")
        assertThat(result.additionalFlights).isEqualTo(1)
    }

    @Test
    fun `repeated mentions of the same flight are not counted as extra segments`() {
        val result =
            extractor.extract(
                """
                Flight AI 0865 New Delhi (DEL) - Mumbai (BOM)
                Fare summary for AI 0865: INR 7,845
                """.trimIndent(),
            )

        assertThat(result.flightNumber.value).isEqualTo("865")
        assertThat(result.additionalFlights).isEqualTo(0)
    }

    @Test
    fun `ota booking reference is used at medium confidence when no airline pnr is printed`() {
        val result =
            extractor.extract(
                """
                Booking ID: NF712345678901
                Traveller: MR DEV PATEL
                6E 6114 BLR - HYD Date: 05-11-2026
                """.trimIndent(),
            )

        assertThat(result.pnr.value).isEqualTo("NF712345678901")
        assertThat(result.pnr.confidence).isEqualTo(ExtractionConfidence.MEDIUM)
    }
}
