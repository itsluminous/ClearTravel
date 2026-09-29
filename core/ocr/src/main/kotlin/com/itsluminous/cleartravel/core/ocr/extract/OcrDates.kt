package com.itsluminous.cleartravel.core.ocr.extract

import java.time.LocalDate
import kotlin.math.abs

/**
 * Tolerant date parsing for OCR/SMS text. Handles the formats seen on IRCTC tickets,
 * SMS ("20-09-25"), boarding passes ("20SEP25", "20 SEP 2025", "20SEP" without a
 * year), ISO dates ("2026-06-12") and month-first prose ("Jun 12, 2026"). Output is
 * always ISO-8601 `yyyy-MM-dd`, or null when unparseable.
 *
 * Public (ADR-042) so feature-level text parsers — the flight SMS/email parser in
 * `feature:flights` — reuse it instead of growing their own date table.
 */
object OcrDates {
    private val MONTHS =
        listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** `2026-06-12` — unambiguous, so it is tried first. */
    private val ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    /** `20SEP25`, `15-Aug-2025`, `20 SEP` (year resolved nearest to [today]). */
    private val DAY_MONTH_NAME =
        Regex("""\b(\d{1,2})\s*[-/. ]?\s*([A-Za-z]{3})[a-z]*\.?\s*[-/. ,]?\s*(\d{2,4})?\b""")

    /** `Jun 12, 2026`, `June 12 2026` — month-first prose (airline emails). */
    private val MONTH_NAME_DAY =
        Regex("""\b([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""")

    /** `20-09-2025`, `20/09/25` (day-first, as printed on Indian tickets). */
    private val DAY_MONTH_NUMERIC = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")

    fun parseToIso(
        raw: String,
        today: LocalDate = LocalDate.now(),
    ): String? {
        for (m in ISO.findAll(raw)) {
            val year = m.groupValues[1].toIntOrNull() ?: continue
            val month = m.groupValues[2].toIntOrNull() ?: continue
            val day = m.groupValues[3].toIntOrNull() ?: continue
            toDate(day, month, year)?.let { return it.toString() }
        }
        for (m in DAY_MONTH_NAME.findAll(raw)) {
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val month = MONTHS.indexOf(m.groupValues[2].uppercase()) + 1
            if (month == 0) continue
            val date =
                m.groupValues[3].toIntOrNull()?.let { toDate(day, month, normalizeYear(it)) }
                    ?: nearestYearDate(day, month, today)
            if (date != null) return date.toString()
        }
        for (m in MONTH_NAME_DAY.findAll(raw)) {
            val month = MONTHS.indexOf(m.groupValues[1].uppercase()) + 1
            if (month == 0) continue
            val day = m.groupValues[2].toIntOrNull() ?: continue
            val year = m.groupValues[3].toIntOrNull() ?: continue
            toDate(day, month, year)?.let { return it.toString() }
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
