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
 * extracted and compared against its expected-output JSON (ADR-003 discipline).
 */
@RunWith(Parameterized::class)
class BoardingPassTextExtractorFixturesTest(
    private val fixture: String,
) {
    private val extractor = BoardingPassTextExtractor()

    @Test
    fun extractsExpectedFields() {
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
        fun fixtures() = listOf("boarding-pass-1", "boarding-pass-2")
    }
}

/** Non-parameterized companion cases: garbage fallback + structural never-throw. */
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
}
