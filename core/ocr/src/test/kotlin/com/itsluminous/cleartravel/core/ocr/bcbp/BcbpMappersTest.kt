package com.itsluminous.cleartravel.core.ocr.bcbp

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import org.junit.Test
import java.time.LocalDate

class BcbpMappersTest {
    @Test
    fun `maps first leg to a high confidence barcode extraction`() {
        val raw = "M1DESMARAIS/LUC       EABC123 YULFRAAC 0834 326J001A0025 100"
        val data =
            (BcbpParser.parse(raw, today = LocalDate.of(2025, 11, 1)) as BcbpParseResult.Success)
                .data

        val extraction = data.toBoardingPassExtraction()

        assertThat(extraction.source).isEqualTo(BoardingPassSource.BARCODE)
        assertThat(extraction.passengerName.value).isEqualTo("LUC DESMARAIS")
        assertThat(extraction.pnr.value).isEqualTo("ABC123")
        assertThat(extraction.carrier.value).isEqualTo("AC")
        assertThat(extraction.flightNumber.value).isEqualTo("834")
        assertThat(extraction.fromAirport.value).isEqualTo("YUL")
        assertThat(extraction.toAirport.value).isEqualTo("FRA")
        assertThat(extraction.flightDate.value).isEqualTo("2025-11-22")
        assertThat(extraction.seat.value).isEqualTo("1A")
        assertThat(extraction.sequenceNumber.value).isEqualTo("25")
        assertThat(extraction.pnr.confidence).isEqualTo(ExtractionConfidence.HIGH)
        assertThat(extraction.passengerName.confidence).isEqualTo(ExtractionConfidence.HIGH)
    }

    @Test
    fun `no legs maps to the empty extraction`() {
        val extraction = BcbpData(passengerName = "X/Y", legs = emptyList()).toBoardingPassExtraction()
        assertThat(extraction).isEqualTo(BoardingPassExtraction.EMPTY)
    }
}
