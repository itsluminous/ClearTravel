package com.itsluminous.cleartravel.core.ocr.pipeline

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.ocr.FixtureLoader
import org.junit.Test

/**
 * Row reconstruction from real ML Kit geometry. `irctc-ticket-3.geometry.txt` is the
 * line-by-line output (`left|top|right|bottom|text`) ML Kit produced on-device for a
 * real IRCTC ERS PDF (names anonymized); `irctc-ticket-3.txt` is the row text the
 * extractor fixture consumes — this test pins the two together so a layout change
 * that reshuffles cells is caught before the extractor fixture goes stale.
 */
class OcrLayoutTest {
    @Test
    fun `rebuilds the ERS reading order from the recorded geometry`() {
        val lines =
            FixtureLoader
                .read("irctc-ticket-3.geometry.txt")
                .lines()
                .filter { it.startsWith("G|") }
                .map { raw ->
                    val parts = raw.split("|", limit = 6)
                    OcrLine(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt(), parts[5])
                }

        val rows = OcrLayout.toRowText(lines)

        assertThat(rows).isEqualTo(FixtureLoader.read("irctc-ticket-3.txt").trimEnd())
        // The stacked ERS table: label row directly above its value row, cells aligned.
        assertThat(rows).contains("PNR  Train No./Name  Class\n8553674906  20933 /UDN DANAPUR EXP  SECOND AC (2A)")
        assertThat(rows).contains("1. RAMESH KUMAR  59  M  RAC/12  RAC/11")
    }

    @Test
    fun `cells on one visual row are ordered left to right`() {
        val rows =
            OcrLayout.toRowText(
                listOf(
                    OcrLine(500, 10, 600, 30, "right"),
                    OcrLine(10, 12, 100, 32, "left"),
                    OcrLine(10, 60, 100, 80, "second row"),
                ),
            )
        assertThat(rows).isEqualTo("left  right\nsecond row")
    }

    @Test
    fun `empty and blank input yield an empty string`() {
        assertThat(OcrLayout.toRowText(emptyList())).isEmpty()
        assertThat(OcrLayout.toRowText(listOf(OcrLine(0, 0, 10, 10, "   ")))).isEmpty()
    }
}
