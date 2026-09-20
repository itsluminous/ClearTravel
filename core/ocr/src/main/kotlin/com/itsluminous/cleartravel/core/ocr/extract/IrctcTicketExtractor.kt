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
 */
@Singleton
class IrctcTicketExtractor
    @Inject
    constructor() {
        fun extract(text: String): TrainTicketExtraction = runCatching { extractInternal(text) }.getOrDefault(TrainTicketExtraction.EMPTY)

        private fun extractInternal(text: String): TrainTicketExtraction {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val (trainNumber, trainName) = extractTrain(text)
            return TrainTicketExtraction(
                pnr = extractPnr(text),
                trainNumber = trainNumber,
                trainName = trainName,
                journeyDate = extractJourneyDate(lines),
                fromStation = extractStation(text, FROM_STATION),
                toStation = extractStation(text, TO_STATION),
                travelClass = extractClass(text),
                quota = extractQuota(text),
                passengers = extractPassengers(lines),
            )
        }

        private fun extractPnr(text: String): ExtractedField {
            LABELED_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            BARE_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.MEDIUM)
            }
            return ExtractedField.EMPTY
        }

        private fun extractTrain(text: String): Pair<ExtractedField, ExtractedField> {
            LABELED_TRAIN.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.HIGH) to
                    ExtractedField.of(m.groupValues[2].trim(), ExtractionConfidence.HIGH)
            }
            NUMBER_SLASH_NAME.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM) to
                    ExtractedField.of(m.groupValues[2].trim(), ExtractionConfidence.MEDIUM)
            }
            return ExtractedField.EMPTY to ExtractedField.EMPTY
        }

        private fun extractJourneyDate(lines: List<String>): ExtractedField {
            // Only trust dates on lines with a journey label — tickets also print
            // booking dates, which must never prefill the journey-date field.
            val labeled = lines.firstOrNull { JOURNEY_DATE_LABEL.containsMatchIn(it) }
            val iso = labeled?.let { OcrDates.parseToIso(it) } ?: return ExtractedField.EMPTY
            return ExtractedField.of(iso, ExtractionConfidence.HIGH)
        }

        private fun extractStation(
            text: String,
            pattern: Regex,
        ): ExtractedField =
            pattern
                .find(text)
                ?.let { ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH) }
                ?: ExtractedField.EMPTY

        private fun extractClass(text: String): ExtractedField =
            LABELED_CLASS
                .find(text)
                ?.let { ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH) }
                ?: ExtractedField.EMPTY

        private fun extractQuota(text: String): ExtractedField {
            val m = LABELED_QUOTA.find(text) ?: return ExtractedField.EMPTY
            // Prefer the short code in parentheses ("GENERAL (GN)" -> "GN").
            val value = m.groupValues[2].ifBlank { m.groupValues[1].trim() }
            return ExtractedField.of(value, ExtractionConfidence.HIGH)
        }

        private fun extractPassengers(lines: List<String>): List<PassengerExtraction> =
            lines.mapNotNull { line ->
                val m = PASSENGER_LINE.find(line) ?: return@mapNotNull null
                val status = m.groupValues[3].trim()
                val statusParts = STATUS_PARTS.find(status)
                PassengerExtraction(
                    name = ExtractedField.of(m.groupValues[2].trim(), ExtractionConfidence.HIGH),
                    coach =
                        ExtractedField.of(
                            statusParts?.groupValues?.get(2),
                            ExtractionConfidence.MEDIUM,
                        ),
                    berth =
                        ExtractedField.of(
                            statusParts?.groupValues?.get(3),
                            ExtractionConfidence.MEDIUM,
                        ),
                    bookingStatus = ExtractedField.of(status, ExtractionConfidence.HIGH),
                )
            }

        private companion object {
            val LABELED_PNR =
                Regex("""PNR\s*(?:No\.?|Number)?\s*[:\-]?\s*(\d{10})""", RegexOption.IGNORE_CASE)
            val BARE_PNR = Regex("""\b(\d{10})\b""")
            val LABELED_TRAIN =
                Regex(
                    """Train\s*(?:No\.?|Number)?\s*(?:[&/]|and)?\s*(?:Name)?\s*[:\-]?\s*""" +
                        """(\d{5})\s*[/\-]\s*([A-Z][A-Z0-9 .\-]{2,40})""",
                    RegexOption.IGNORE_CASE,
                )
            val NUMBER_SLASH_NAME = Regex("""\b(\d{5})\s*/\s*([A-Z][A-Z0-9 .\-]{2,40})""")
            val JOURNEY_DATE_LABEL =
                Regex("""(?:Date\s*of\s*Journey|Journey\s*Date|DOJ)""", RegexOption.IGNORE_CASE)
            val FROM_STATION =
                Regex(
                    """\b(?:From|Boarding\s*At)\s*[:\-]?\s*[A-Z .]*\(([A-Z]{2,5})\)""",
                    RegexOption.IGNORE_CASE,
                )
            val TO_STATION =
                Regex("""\bTo\s*[:\-]?\s*[A-Z .]*\(([A-Z]{2,5})\)""", RegexOption.IGNORE_CASE)
            val LABELED_CLASS =
                Regex(
                    """Class\s*[:\-]?\s*(1A|2A|3A|3E|EA|EC|CC|SL|2S|FC)\b""",
                    RegexOption.IGNORE_CASE,
                )
            val LABELED_QUOTA =
                Regex(
                    """Quota\s*[:\-]?\s*([A-Z ]{2,20}?)\s*(?:\(([A-Z]{2})\))?\s*(?:$|\n|\s{2,})""",
                    RegexOption.IGNORE_CASE,
                )

            /** `1. RAHUL SHARMA  34  M  CNF/B4/32/LB ...` (booking + current status columns). */
            val PASSENGER_LINE =
                Regex(
                    """^(\d{1,2})[.)]?\s+([A-Z][A-Z .]{2,40}?)\s+\d{1,3}\s+[MF]\s+""" +
                        """((?:CNF|RAC|WL|CAN)[A-Z0-9/]*)""",
                )

            /** `CNF/B4/32/LOWER` -> status / coach / berth. */
            val STATUS_PARTS =
                Regex("""(CNF|RAC|WL|CAN)\s*/\s*([A-Z]{1,3}\d{1,2})\s*/\s*(\d{1,3})""")
        }
    }
