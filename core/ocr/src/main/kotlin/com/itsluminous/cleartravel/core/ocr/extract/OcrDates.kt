package com.itsluminous.cleartravel.core.ocr.extract

import java.time.LocalDate
import kotlin.math.abs

/**
 * Tolerant date parsing for OCR/SMS text. Handles the formats seen on IRCTC tickets,
 * SMS ("20-09-25") and boarding passes ("20SEP25", "20 SEP 2025", "20SEP" without a
 * year). Output is always ISO-8601 `yyyy-MM-dd`, or null when unparseable.
 */
internal object OcrDates {
    private val MONTHS =
        listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** `20SEP25`, `15-Aug-2025`, `20 SEP` (year resolved nearest to [today]). */
    private val DAY_MONTH_NAME =
        Regex("""\b(\d{1,2})\s*[-/. ]?\s*([A-Za-z]{3})[a-z]*\s*[-/. ,]?\s*(\d{2,4})?\b""")

    /** `20-09-2025`, `20/09/25` (day-first, as printed on Indian tickets). */
    private val DAY_MONTH_NUMERIC = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")

    fun parseToIso(
        raw: String,
        today: LocalDate = LocalDate.now(),
    ): String? {
        for (m in DAY_MONTH_NAME.findAll(raw)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val month = MONTHS.indexOf(m.groupValues[2].uppercase()) + 1
            if (month == 0) continue
            val date =
                m.groupValues[3].toIntOrNull()?.let { toDate(day, month, normalizeYear(it)) }
                    ?: nearestYearDate(day, month, today)
            if (date != null) return date.toString()
        }
        for (m in DAY_MONTH_NUMERIC.findAll(raw)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val month = m.groupValues[2].toIntOrNull() ?: continue
            val year = m.groupValues[3].toIntOrNull() ?: continue
            val date = toDate(day, month, normalizeYear(year))
            if (date != null) return date.toString()
        }
        return null
    }

    private fun normalizeYear(year: Int): Int = if (year < 100) 2000 + year else year

    private fun toDate(
        day: Int,
        month: Int,
        year: Int,
    ): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** No year printed: pick the candidate (last/this/next year) closest to [today]. */
    private fun nearestYearDate(
        day: Int,
        month: Int,
        today: LocalDate,
    ): LocalDate? =
        (-1..1)
            .mapNotNull { delta -> toDate(day, month, today.year + delta) }
            .minByOrNull { abs(it.toEpochDay() - today.toEpochDay()) }
}
