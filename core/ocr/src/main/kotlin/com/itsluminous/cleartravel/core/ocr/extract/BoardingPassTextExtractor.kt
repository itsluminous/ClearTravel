package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure heuristic extractor for boarding-pass OCR text — the fallback when no BCBP
 * barcode is found or it fails to decode. Labeled matches are HIGH confidence,
 * strong unlabeled structural matches MEDIUM, weak heuristics LOW. Always succeeds
 * structurally: garbage input returns [BoardingPassExtraction.EMPTY].
 */
@Singleton
class BoardingPassTextExtractor
    @Inject
    constructor() {
        fun extract(text: String): BoardingPassExtraction = runCatching { extractInternal(text) }.getOrDefault(BoardingPassExtraction.EMPTY)

        private fun extractInternal(text: String): BoardingPassExtraction {
            val upper = text.uppercase()
            val flight = extractFlight(upper)
            val route = extractRoute(upper)
            val result =
                BoardingPassExtraction(
                    passengerName = extractName(upper),
                    pnr = extractPnr(upper),
                    carrier = flight.first,
                    flightNumber = flight.second,
                    fromAirport = route.first,
                    toAirport = route.second,
                    flightDate = extractDate(upper),
                    seat = extractSeat(upper),
                    sequenceNumber = extractSequence(upper),
                    source = BoardingPassSource.OCR_TEXT,
                )
            // Nothing recognized at all -> canonical EMPTY (source NONE, blank form).
            return if (result.copy(source = BoardingPassSource.NONE).isEmpty) {
                BoardingPassExtraction.EMPTY
            } else {
                result
            }
        }

        private fun extractName(text: String): ExtractedField {
            LABELED_NAME.find(text)?.let {
                return ExtractedField.of(
                    displayName(it.groupValues[1]),
                    ExtractionConfidence.HIGH,
                )
            }
            SLASH_NAME.find(text)?.let {
                return ExtractedField.of(
                    displayName(it.groupValues[1]),
                    ExtractionConfidence.MEDIUM,
                )
            }
            return ExtractedField.EMPTY
        }

        /** `SHARMA/RAHUL MR` -> `RAHUL SHARMA MR`-less display form `RAHUL SHARMA`. */
        private fun displayName(raw: String): String {
            val slash = raw.indexOf('/')
            if (slash < 0) return raw.trim()
            val last = raw.substring(0, slash).trim()
            val first =
                raw
                    .substring(slash + 1)
                    .trim()
                    .removeSuffix(" MR")
                    .removeSuffix(" MRS")
                    .removeSuffix(" MS")
            return listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun extractPnr(text: String): ExtractedField {
            LABELED_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            // Unlabeled 6-char alphanumeric with both letters and digits is a weak
            // signal (could be anything on the pass).
            BARE_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        private fun extractFlight(text: String): Pair<ExtractedField, ExtractedField> {
            LABELED_FLIGHT.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.HIGH) to
                    ExtractedField.of(
                        m.groupValues[2].trimStart('0'),
                        ExtractionConfidence.HIGH,
                    )
            }
            BARE_FLIGHT.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM) to
                    ExtractedField.of(
                        m.groupValues[2].trimStart('0'),
                        ExtractionConfidence.MEDIUM,
                    )
            }
            return ExtractedField.EMPTY to ExtractedField.EMPTY
        }

        private fun extractRoute(text: String): Pair<ExtractedField, ExtractedField> {
            LABELED_ROUTE.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.HIGH) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.HIGH)
            }
            ARROW_ROUTE.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.MEDIUM)
            }
            return ExtractedField.EMPTY to ExtractedField.EMPTY
        }

        private fun extractDate(text: String): ExtractedField {
            LABELED_DATE.find(text)?.let { m ->
                OcrDates.parseToIso(m.groupValues[1])?.let {
                    return ExtractedField.of(it, ExtractionConfidence.HIGH)
                }
            }
            OcrDates.parseToIso(text)?.let {
                return ExtractedField.of(it, ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        private fun extractSeat(text: String): ExtractedField {
            LABELED_SEAT.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            BARE_SEAT.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        private fun extractSequence(text: String): ExtractedField =
            LABELED_SEQ
                .find(text)
                ?.let {
                    ExtractedField.of(
                        it.groupValues[1].trimStart('0'),
                        ExtractionConfidence.HIGH,
                    )
                }
                ?: ExtractedField.EMPTY

        private companion object {
            val LABELED_NAME =
                Regex(
                    """(?:NAME[ ]+OF[ ]+PASSENGER|PASSENGER(?:[ ]+NAME)?|NAME)[ ]*[:\-]?[ ]*""" +
                        """([A-Z]{2,25}/[A-Z]{2,25}(?:[ ]+[A-Z]{1,10})?|[A-Z]+(?:[ ]+[A-Z]+)+)""",
                )
            val SLASH_NAME = Regex("""\b([A-Z]{2,25}/[A-Z]{2,25}(?:[ ]+MRS?|[ ]+MS)?)\b""")
            val LABELED_PNR =
                Regex(
                    """(?:PNR|BOOKING\s*REF(?:ERENCE)?|CONFIRMATION|RECORD\s*LOCATOR)""" +
                        """\s*(?:NO\.?|NUMBER)?\s*[:#\-]?\s*([A-Z0-9]{6})\b""",
                )
            val BARE_PNR = Regex("""\b(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)([A-Z0-9]{6})\b""")
            val LABELED_FLIGHT =
                Regex("""FLIGHT\s*(?:NO\.?|NUMBER)?\s*[:\-]?\s*([A-Z][A-Z0-9]|\d[A-Z])\s*-?\s*(\d{1,4})\b""")
            val BARE_FLIGHT = Regex("""\b([A-Z][A-Z0-9]|\d[A-Z])\s?-?\s?(\d{2,4})\b""")
            val LABELED_ROUTE =
                Regex("""FROM\s*[:\-]?\s*[A-Z ]*\(?([A-Z]{3})\)?\s+TO\s*[:\-]?\s*[A-Z ]*\(?([A-Z]{3})\)?""")
            val ARROW_ROUTE = Regex("""\b([A-Z]{3})\s*(?:->|→|—|–|-|\bTO\b)\s*([A-Z]{3})\b""")
            val LABELED_DATE =
                Regex("""DATE\s*[:\-]?\s*([0-9]{1,2}\s*[-/. ]?\s*[A-Z]{3,9}\s*[-/. ,]?\s*[0-9]{0,4})""")
            val LABELED_SEAT = Regex("""SEAT\s*(?:NO\.?)?\s*[:\-]?\s*(\d{1,3}[A-K])\b""")
            val BARE_SEAT = Regex("""\b(\d{1,2}[A-F])\b""")
            val LABELED_SEQ = Regex("""SEQ(?:UENCE)?\s*(?:NO\.?)?\s*[:\-]?\s*(\d{1,5})\b""")
        }
    }
