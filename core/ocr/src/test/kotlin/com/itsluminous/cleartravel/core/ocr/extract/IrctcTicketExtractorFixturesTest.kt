package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Fixture harness (same discipline as the scrape rules, ADR-003): every recorded
 * IRCTC OCR-text fixture is extracted and compared against its expected-output JSON.
 */
@RunWith(Parameterized::class)
class IrctcTicketExtractorFixturesTest(
    private val fixture: String,
) {
    private val extractor = IrctcTicketExtractor()

    @Test
    fun extractsExpectedFields() {
        val text = FixtureLoader.read("$fixture.txt")
        val expected =
            Json.decodeFromString<TrainTicketExtraction>(
                FixtureLoader.read("$fixture.expected.json"),
            )

        assertThat(extractor.extract(text)).isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures() = listOf("irctc-ticket-1", "irctc-ticket-2", "irctc-ticket-3")
    }
}

/** Non-parameterized companion cases: garbage fallback + structural never-throw. */
class IrctcTicketExtractorFallbackTest {
    private val extractor = IrctcTicketExtractor()

    @Test
    fun `garbage fixture falls back to the empty result`() {
        val result = extractor.extract(FixtureLoader.read("garbage.txt"))
        assertThat(result).isEqualTo(TrainTicketExtraction.EMPTY)
        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun `empty and hostile input never throw`() {
        assertThat(extractor.extract("")).isEqualTo(TrainTicketExtraction.EMPTY)
        assertThat(extractor.extract("\u0000\uFFFD\n\t ~~~ ((( ")).isEqualTo(TrainTicketExtraction.EMPTY)
    }
}
