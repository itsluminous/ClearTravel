package com.itsluminous.cleartravel.core.ocr.extract

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class OcrDatesTest {
    private val today = LocalDate.of(2025, 9, 20)

    @Test
    fun `parses dd-MMM-yyyy`() {
        assertThat(OcrDates.parseToIso("20-Sep-2025", today)).isEqualTo("2025-09-20")
    }

    @Test
    fun `parses compact boarding pass form ddMMMyy`() {
        assertThat(OcrDates.parseToIso("20SEP25", today)).isEqualTo("2025-09-20")
    }

    @Test
    fun `parses spaced form dd MMM yyyy`() {
        assertThat(OcrDates.parseToIso("05 OCT 2025", today)).isEqualTo("2025-10-05")
    }

    @Test
    fun `parses numeric dd-mm-yy day first`() {
        assertThat(OcrDates.parseToIso("20-09-25", today)).isEqualTo("2025-09-20")
    }

    @Test
    fun `parses numeric dd slash mm slash yyyy`() {
        assertThat(OcrDates.parseToIso("05/10/2025", today)).isEqualTo("2025-10-05")
    }

    @Test
    fun `missing year resolves to the nearest year`() {
        assertThat(OcrDates.parseToIso("02 JAN", LocalDate.of(2025, 12, 28)))
            .isEqualTo("2026-01-02")
    }

    @Test
    fun `skips a spurious match and finds a later valid date`() {
        assertThat(OcrDates.parseToIso("42 ITEMS THEN 20-Sep-2025", today))
            .isEqualTo("2025-09-20")
    }

    @Test
    fun `parses ISO yyyy-MM-dd`() {
        assertThat(OcrDates.parseToIso("Your flight on 2026-06-12 departs at 06:35", today)).isEqualTo("2026-06-12")
    }

    @Test
    fun `parses month-first prose MMM d, yyyy`() {
        assertThat(OcrDates.parseToIso("Departure: Jun 12, 2026", today)).isEqualTo("2026-06-12")
        assertThat(OcrDates.parseToIso("June 12th 2026", today)).isEqualTo("2026-06-12")
    }

    @Test
    fun `parses two-digit year with month name`() {
        assertThat(OcrDates.parseToIso("to VNS on 29 May 26.", today)).isEqualTo("2026-05-29")
        assertThat(OcrDates.parseToIso("29-May-26", today)).isEqualTo("2026-05-29")
    }

    @Test
    fun `flight numbers and times are not mistaken for dates`() {
        assertThat(OcrDates.parseToIso("flight QP 1421 PNR X4F18V dep 06:35", today)).isNull()
    }

    @Test
    fun `invalid calendar day returns null`() {
        assertThat(OcrDates.parseToIso("32-13-2025", today)).isNull()
    }

    @Test
    fun `garbage returns null`() {
        assertThat(OcrDates.parseToIso("the quick brown fox 42", today)).isNull()
    }
}
