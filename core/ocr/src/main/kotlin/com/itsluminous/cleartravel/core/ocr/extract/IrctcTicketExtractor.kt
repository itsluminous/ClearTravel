package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.PassengerExtraction
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure heuristic extractor for IRCTC ticket (ERS) OCR text. Labeled matches are HIGH
 * confidence, strong unlabeled structural matches MEDIUM, weak heuristics LOW.
 * Always succeeds structurally: garbage input returns [TrainTicketExtraction.EMPTY].
 *
 * Input is the row-ordered text produced by the OCR pipeline (`OcrLayout`): one
 * visual row per line, cells separated by two or more spaces. Two ERS layouts are
 * handled — INLINE labels (`PNR No: 8524167890`, older/print layouts) and STACKED
 * tables (a header row `PNR  Train No./Name  Class` above a value row
 * `8553674906  20933 /UDN DANAPUR EXP  SECOND AC (2A)`, the current IRCTC PDF), where
 * a value is found by column index under its label.
 */
@Singleton
class IrctcTicketExtractor
    @Inject
    constructor() {
        fun extract(text: String): TrainTicketExtraction = runCatching { extractInternal(text) }.getOrDefault(TrainTicketExtraction.EMPTY)

        private fun extractInternal(text: String): TrainTicketExtraction {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val rows = lines.map { line -> line.split(CELL_SPLIT).map(String::trim).filter(String::isNotEmpty) }
            val cells = rows.flatten()
            val (trainNumber, trainName) = extractTrain(text, cells, rows)
            return TrainTicketExtraction(
                pnr = extractPnr(text, cells, rows),
                trainNumber = trainNumber,
                trainName = trainName,
                journeyDate = extractJourneyDate(cells, rows),
                fromStation = extractStation(cells, rows, FROM_STATION, FROM_LABELS),
                toStation = extractStation(cells, rows, TO_STATION, TO_LABEL),
                travelClass = extractClass(cells, rows),
                quota = extractQuota(cells, rows),
                passengers = extractPassengers(lines),
            )
        }

        private fun extractPnr(
            text: String,
            cells: List<String>,
            rows: List<List<String>>,
        ): ExtractedField {
            cells.firstNotNullOfOrNull { LABELED_PNR.find(it) }?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            stackedValue(rows, PNR_LABEL)?.let { value ->
                TEN_DIGITS.find(value)?.let { return ExtractedField.of(it.value, ExtractionConfidence.HIGH) }
            }
            BARE_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.MEDIUM)
            }
            return ExtractedField.EMPTY
        }

        private fun extractTrain(
            text: String,
            cells: List<String>,
            rows: List<List<String>>,
        ): Pair<ExtractedField, ExtractedField> {
            cells.firstNotNullOfOrNull { LABELED_TRAIN.find(it) }?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.HIGH) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.HIGH)
            }
            stackedValue(rows, TRAIN_LABEL)?.let { value ->
                NUMBER_SLASH_NAME.find(value)?.let { m ->
                    return ExtractedField.of(m.groupValues[1], ExtractionConfidence.HIGH) to
                        ExtractedField.of(m.groupValues[2], ExtractionConfidence.HIGH)
                }
            }
            NUMBER_SLASH_NAME.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.MEDIUM)
            }
            return ExtractedField.EMPTY to ExtractedField.EMPTY
        }

        private fun extractJourneyDate(
            cells: List<String>,
            rows: List<List<String>>,
        ): ExtractedField {
            // Only trust dates next to a journey label — tickets also print booking
            // dates, which must never prefill the journey-date field. The label's own
            // CELL is parsed (not the whole row: the ERS prints departure/arrival
            // dates on the same row), then the stacked value below a header label.
            val labeled = cells.firstOrNull { JOURNEY_DATE_LABEL.containsMatchIn(it) }
            val iso =
                labeled?.let { OcrDates.parseToIso(it) }
                    ?: stackedValue(rows, JOURNEY_DATE_LABEL)?.let { OcrDates.parseToIso(it) }
                    ?: return ExtractedField.EMPTY
            return ExtractedField.of(iso, ExtractionConfidence.HIGH)
        }

        private fun extractStation(
            cells: List<String>,
            rows: List<List<String>>,
            inline: Regex,
            labels: List<Regex>,
        ): ExtractedField {
            cells.firstNotNullOfOrNull { inline.find(it) }?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            for (label in labels) {
                val value = stackedValue(rows, label) ?: continue
                STATION_CODE.find(value)?.let {
                    return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
                }
            }
            return ExtractedField.EMPTY
        }

        private fun extractClass(
            cells: List<String>,
            rows: List<List<String>>,
        ): ExtractedField {
            val value =
                cells.firstNotNullOfOrNull { LABELED_CLASS.find(it)?.groupValues?.get(1) }
                    ?: stackedValue(rows, CLASS_LABEL)
                    ?: return ExtractedField.EMPTY
            // `SECOND AC (2A)` / `3A (THIRD AC)` / `SL` -> the short class code.
            val code = CLASS_CODE.find(value)?.groupValues?.get(1) ?: return ExtractedField.EMPTY
            return ExtractedField.of(code.uppercase(), ExtractionConfidence.HIGH)
        }

        private fun extractQuota(
            cells: List<String>,
            rows: List<List<String>>,
        ): ExtractedField {
            val value =
                cells.firstNotNullOfOrNull { LABELED_QUOTA.find(it)?.groupValues?.get(1) }
                    ?: stackedValue(rows, QUOTA_LABEL)
                    ?: return ExtractedField.EMPTY
            // Prefer the short code in parentheses ("GENERAL (GN)" -> "GN").
            val code = PAREN_CODE.find(value)?.groupValues?.get(1) ?: value.trim()
            return ExtractedField.of(code, ExtractionConfidence.HIGH)
        }

        private fun extractPassengers(lines: List<String>): List<PassengerExtraction> =
            lines.mapNotNull { line ->
                val m = PASSENGER_LINE.find(line) ?: return@mapNotNull null
                val booking = m.groupValues[3].trim()
                val current = m.groupValues[4].trim()
                // Coach/berth come from whichever status column carries them: a
                // confirmed booking (`CNF/B4/32/LB`) or a later confirmation of a
                // waitlisted/RAC booking. Plain `RAC/12` / `WL/1` have neither yet.
                val parts = STATUS_PARTS.find(booking) ?: STATUS_PARTS.find(current)
                PassengerExtraction(
                    name = ExtractedField.of(m.groupValues[2].trim(), ExtractionConfidence.HIGH),
                    coach = ExtractedField.of(parts?.groupValues?.get(2), ExtractionConfidence.MEDIUM),
                    berth = ExtractedField.of(parts?.groupValues?.get(3), ExtractionConfidence.MEDIUM),
                    bookingStatus = ExtractedField.of(booking, ExtractionConfidence.HIGH),
                    currentStatus = ExtractedField.of(current, ExtractionConfidence.HIGH),
                )
            }

        /**
         * Value printed UNDER [label] in a stacked header-row / value-row table: the
         * cell at the same column index on the next row when the two rows have the
         * same number of cells (or both are single-cell). Null when [label] is not a
         * whole header cell anywhere or the rows don't line up.
         */
        private fun stackedValue(
            rows: List<List<String>>,
            label: Regex,
        ): String? {
            for (i in 0 until rows.size - 1) {
                val header = rows[i]
                val column = header.indexOfFirst { label.matches(it) }
                if (column < 0) continue
                val below = rows[i + 1]
                val value =
                    when {
                        below.size == header.size -> below[column]
                        header.size == 1 && below.size == 1 -> below[0]
                        else -> null
                    } ?: continue
                if (!label.matches(value)) return value
            }
            return null
        }

        private companion object {
            /** Two or more spaces separate the cells of one visual row (`OcrLayout`). */
            val CELL_SPLIT = Regex("""\s{2,}""")
            val TEN_DIGITS = Regex("""\b\d{10}\b""")
            val BARE_PNR = Regex("""\b(\d{10})\b""")
            val STATION_CODE = Regex("""\(([A-Z]{2,5})\)""")
            val PAREN_CODE = Regex("""\(([A-Z]{2,3})\)""")
            val CLASS_CODE = Regex("""\b(1A|2A|3A|3E|EA|EC|CC|SL|2S|FC)\b""", RegexOption.IGNORE_CASE)

            // ---- inline labels: `Label: value` inside one cell ----
            val LABELED_PNR =
                Regex("""PNR\s*(?:No\.?|Number)?\s*[:\-]?\s*(\d{10})""", RegexOption.IGNORE_CASE)
            val LABELED_TRAIN =
                Regex(
                    """Train\s*(?:No\.?|Number)?\s*(?:[&/]|and)?\s*(?:Name)?\s*[:\-]?\s*""" +
                        """(\d{5})\s*[/\-]\s*([A-Z][A-Z0-9.\-]*(?: [A-Z0-9.\-]+){0,8})""",
                    RegexOption.IGNORE_CASE,
                )

            /** Train name = single-space words only, so it never runs into the next cell. */
            val NUMBER_SLASH_NAME = Regex("""\b(\d{5})\s*/\s*([A-Z][A-Z0-9.\-]*(?: [A-Z0-9.\-]+){0,8})""")
            val JOURNEY_DATE_LABEL =
                Regex("""(?:Date\s*of\s*Journey|Journey\s*Date|Start\s*Date|DOJ)\*?""", RegexOption.IGNORE_CASE)

            // Station CODES stay case-sensitive (`(?-i:…)`) so `Total Fare (all
            // inclusive)` can never read as destination "all".
            val FROM_STATION =
                Regex(
                    """\b(?:From|Boarding\s*At)\b\s*[:\-]?\s*[A-Z .]*\((?-i:([A-Z]{2,5}))\)""",
                    RegexOption.IGNORE_CASE,
                )
            val TO_STATION =
                Regex("""\bTo\b\s*[:\-]?\s*[A-Z .]*\((?-i:([A-Z]{2,5}))\)""", RegexOption.IGNORE_CASE)
            val LABELED_CLASS =
                Regex("""Class\s*[:\-]\s*([A-Z0-9 ()]{2,30})""", RegexOption.IGNORE_CASE)
            val LABELED_QUOTA =
                Regex("""Quota\s*[:\-]\s*([A-Z]+(?: [A-Z]+)*\s*(?:\([A-Z]{2,3}\))?)""", RegexOption.IGNORE_CASE)

            // ---- stacked header labels: the WHOLE header cell ----
            val PNR_LABEL = Regex("""PNR(?:\s*(?:No\.?|Number))?""", RegexOption.IGNORE_CASE)
            val TRAIN_LABEL =
                Regex("""Train\s*(?:No\.?|Number)?\s*(?:[&/]|and)?\s*(?:Name)?""", RegexOption.IGNORE_CASE)
            val CLASS_LABEL = Regex("""Class""", RegexOption.IGNORE_CASE)
            val QUOTA_LABEL = Regex("""Quota""", RegexOption.IGNORE_CASE)

            /** Boarding point first (that's where the journey starts), then the booked origin. */
            val FROM_LABELS =
                listOf(
                    Regex("""Boarding\s*(?:At|Point|Station)""", RegexOption.IGNORE_CASE),
                    Regex("""(?:Booked\s*)?From""", RegexOption.IGNORE_CASE),
                )
            val TO_LABEL = listOf(Regex("""To""", RegexOption.IGNORE_CASE))

            /**
             * `1. RAHUL SHARMA  34  M  CNF/B4/32/LB  CNF/B4/32/LB` — serial, name, age,
             * gender, booking status, optional current status.
             */
            val PASSENGER_LINE =
                Regex(
                    """^(\d{1,2})[.)]?\s+([A-Z][A-Z .]{2,40}?)\s+\d{1,3}\s+[MF]\s+""" +
                        """((?:CNF|RAC|WL|CAN|RLWL|PQWL|RSWL|GNWL)[A-Z0-9/]*)(?:\s+((?:CNF|RAC|WL|CAN|RLWL|PQWL|RSWL|GNWL)[A-Z0-9/]*))?""",
                )

            /** `CNF/B4/32/LOWER` -> status / coach / berth. */
            val STATUS_PARTS =
                Regex("""(CNF|RAC|WL|CAN)\s*/\s*([A-Z]{1,3}\d{1,2})\s*/\s*(\d{1,3})""")
        }
    }
