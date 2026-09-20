package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import kotlinx.serialization.json.Json
import org.junit.Test

class IrctcSmsParserTest {
    private val parser = IrctcSmsParser()

    @Test
    fun `parses the recorded booking sms fixture`() {
        val text = FixtureLoader.read("irctc-sms-1.txt")
        val expected =
            Json.decodeFromString<TrainTicketExtraction>(
                FixtureLoader.read("irctc-sms-1.expected.json"),
            )

        assertThat(parser.parse(text)).isEqualTo(expected)
    }

    @Test
    fun `sms without pnr or train labels is not treated as a booking`() {
        val result = parser.parse("Your OTP is 4821 valid for 10 minutes. Do not share it.")
        assertThat(result).isEqualTo(TrainTicketExtraction.EMPTY)
    }

    @Test
    fun `garbage fixture falls back to the empty result`() {
        assertThat(parser.parse(FixtureLoader.read("garbage.txt")))
            .isEqualTo(TrainTicketExtraction.EMPTY)
    }

    @Test
    fun `empty and hostile input never throw`() {
        assertThat(parser.parse("")).isEqualTo(TrainTicketExtraction.EMPTY)
        assertThat(parser.parse("\u0000\uFFFD ((( ~~~")).isEqualTo(TrainTicketExtraction.EMPTY)
    }
}
