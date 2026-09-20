package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure heuristic extractor for booking-confirmation / e-ticket OCR text (airline
 * direct AND OTA layouts). Same discipline as [BoardingPassTextExtractor]: labeled
 * matches are HIGH confidence, strong unlabeled structural matches MEDIUM, weak
 * heuristics LOW; always succeeds structurally — garbage input returns
 * [BookingConfirmationExtraction.EMPTY].
 *
 * Confirmations often describe MULTIPLE flights (return trips): the FIRST detected
 * flight is extracted fully and the remaining distinct segments are surfaced as
 * `additionalFlights` so the UI can hint "return leg detected — add it separately".
 */
@Singleton
class BookingConfirmationExtractor
    @Inject
    constructor() {
        fun extract(text: String): BookingConfirmationExtraction =
            runCatching { extractInternal(text) }.getOrDefault(BookingConfirmationExtraction.EMPTY)

        private fun extractInternal(text: String): BookingConfirmationExtraction {
            val upper = text.uppercase()
            val flights = detectFlights(upper)
            val first = flights.firstOrNull()
            val route = extractRoute(upper)
            val result =
                BookingConfirmationExtraction(
                    passengerName = extractName(upper),
                    pnr = extractPnr(upper),
                    carrier = first?.carrier ?: ExtractedField.EMPTY,
                    flightNumber = first?.number ?: ExtractedField.EMPTY,
                    fromAirport = route.first,
                    toAirport = route.second,
                    flightDate = extractDate(upper),
                    cabinClass = extractCabin(upper),
                    seat = extractSeat(upper),
                    additionalFlights = (flights.size - 1).coerceAtLeast(0),
                    source = BookingConfirmationSource.OCR_TEXT,
                )
            // Nothing recognized at all -> canonical EMPTY (source NONE, blank form).
            return if (result.copy(source = BookingConfirmationSource.NONE).isEmpty) {
                BookingConfirmationExtraction.EMPTY
            } else {
                result
            }
        }

        private data class FlightToken(
            val carrier: ExtractedField,
            val number: ExtractedField,
        )

        /**
         * All distinct flight segments in document order (dedupe by carrier+number so
         * itinerary tables repeating a flight in fare summaries don't inflate the
         * count). A "FLIGHT"-labeled first match upgrades it to HIGH confidence.
         */
        private fun detectFlights(text: String): List<FlightToken> {
            val labeled =
                LABELED_FLIGHT
                    .findAll(text)
                    .map { it.groupValues[1] to it.groupValues[2].trimStart('0') }
                    .toList()
            val bare =
                BARE_FLIGHT
                    .findAll(text)
                    .map { it.groupValues[1] to it.groupValues[2].trimStart('0') }
                    .toList()
            val labeledKeys = labeled.toSet()
            val distinct = (labeled + bare).distinct()
            return distinct.map { (carrier, number) ->
                val confidence =
                    if (carrier to number in labeledKeys) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM
                FlightToken(
                    carrier = ExtractedField.of(carrier, confidence),
                    number = ExtractedField.of(number, confidence),
                )
            }
        }

        private fun extractName(text: String): ExtractedField {
            LABELED_NAME.find(text)?.let {
                return ExtractedField.of(stripTitle(it.groupValues[1]), ExtractionConfidence.HIGH)
            }
            SLASH_NAME.find(text)?.let {
                return ExtractedField.of(slashDisplayName(it.groupValues[1]), ExtractionConfidence.MEDIUM)
            }
            return ExtractedField.EMPTY
        }

        /** `MR ARJUN NAIR` -> `ARJUN NAIR`; slash forms are reordered separately. */
        private fun stripTitle(raw: String): String {
            val trimmed = raw.trim()
            if ('/' in trimmed) return slashDisplayName(trimmed)
            val parts = trimmed.split(' ').filter { it.isNotEmpty() }
            return parts
                .filterIndexed { index, part -> !(index == 0 && part.trimEnd('.') in TITLES) }
                .joinToString(" ")
        }

        /** `SHARMA/RAHUL MR` -> `RAHUL SHARMA`. */
        private fun slashDisplayName(raw: String): String {
            val slash = raw.indexOf('/')
            if (slash < 0) return raw.trim()
            val last = raw.substring(0, slash).trim()
            val first =
                raw
                    .substring(slash + 1)
                    .trim()
                    .split(' ')
                    .filter { it.trimEnd('.') !in TITLES }
                    .joinToString(" ")
            return listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun extractPnr(text: String): ExtractedField {
            LABELED_AIRLINE_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            // OTA references (MakeMyTrip booking ids, Cleartrip trip ids) are real
            // booking handles but not the airline record locator -> MEDIUM.
            LABELED_OTA_REF.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.MEDIUM)
            }
            BARE_PNR.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        private fun extractRoute(text: String): Pair<ExtractedField, ExtractedField> {
            LABELED_ROUTE.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.HIGH) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.HIGH)
            }
            // `NEW DELHI (DEL) → MUMBAI (BOM)` — parenthesized IATA pair.
            PAREN_ROUTE.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.MEDIUM)
            }
            ARROW_ROUTE.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM) to
                    ExtractedField.of(m.groupValues[2], ExtractionConfidence.MEDIUM)
            }
            // City names without printed IATA codes: keep them (the user reviews the
            // form either way) at LOW confidence.
            CITY_ROUTE.find(text)?.let { m ->
                return ExtractedField.of(m.groupValues[1].trim(), ExtractionConfidence.LOW) to
                    ExtractedField.of(m.groupValues[2].trim(), ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY to ExtractedField.EMPTY
        }

        private fun extractDate(text: String): ExtractedField {
            for (m in LABELED_DATE.findAll(text)) {
                OcrDates.parseToIso(m.groupValues[1])?.let {
                    return ExtractedField.of(it, ExtractionConfidence.HIGH)
                }
            }
            OcrDates.parseToIso(text)?.let {
                return ExtractedField.of(it, ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        private fun extractCabin(text: String): ExtractedField {
            LABELED_CABIN.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
            }
            BARE_CABIN.find(text)?.let {
                return ExtractedField.of(it.groupValues[1], ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        /** Labeled only — seats are usually absent on confirmations, and a bare seat
         *  heuristic false-positives on booking references. */
        private fun extractSeat(text: String): ExtractedField =
            LABELED_SEAT
                .find(text)
                ?.let { ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH) }
                ?: ExtractedField.EMPTY

        private companion object {
            val TITLES = setOf("MR", "MRS", "MS", "MSTR", "MISS", "DR")
            val LABELED_NAME =
                Regex(
                    """(?:NAME[ ]+OF[ ]+PASSENGER|PASSENGER(?:\(S\))?(?:[ ]+NAME)?|TRAVELLERS?|GUEST)""" +
                        """[ ]*\d?\.?[ ]*[:\-][ ]*""" +
                        """((?:MR|MRS|MS|MSTR|MISS|DR)\.?[ ]+[A-Z]+(?:[ ]+[A-Z]+)*|""" +
                        """[A-Z]{2,25}/[A-Z]{2,25}(?:[ ]+[A-Z]{1,10})?|[A-Z]+(?:[ ]+[A-Z]+)+)""",
                )
            val SLASH_NAME = Regex("""\b([A-Z]{2,25}/[A-Z]{2,25}(?:[ ]+MRS?|[ ]+MS)?)\b""")
            val LABELED_AIRLINE_PNR =
                Regex(
                    """(?:AIRLINE[ ]+PNR|PNR|BOOKING[ ]*REF(?:ERENCE)?|CONFIRMATION|RECORD[ ]*LOCATOR)""" +
                        """\s*\)?\s*(?:NO\.?|NUMBER)?\s*\(?(?:PNR)?\)?\s*[:#\-]\s*([A-Z0-9]{6})\b""",
                )
            val LABELED_OTA_REF =
                Regex("""(?:BOOKING[ ]+ID|TRIP[ ]+ID|ORDER[ ]+ID|REFERENCE[ ]+ID)\s*[:#\-]\s*([A-Z]{0,4}\d{6,16})\b""")
            val BARE_PNR = Regex("""\b(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)([A-Z0-9]{6})\b""")
            val LABELED_FLIGHT =
                Regex("""FLIGHT\s*(?:NO\.?|NUMBER)?\s*[:\-]?\s*([A-Z][A-Z0-9]|\d[A-Z])\s*-?\s*(\d{1,4})\b""")
            val BARE_FLIGHT = Regex("""\b([A-Z][A-Z0-9]|\d[A-Z])[ ]?-?[ ]?(\d{2,4})\b""")
            val LABELED_ROUTE =
                Regex("""FROM\s*[:\-]?\s*[A-Z ]*\(?([A-Z]{3})\)?\s+TO\s*[:\-]?\s*[A-Z ]*\(?([A-Z]{3})\)?""")
            val PAREN_ROUTE =
                Regex("""\(([A-Z]{3})\)\s*(?:->|→|—|–|-|\bTO\b)\s*[A-Z ]*\(([A-Z]{3})\)""")
            val ARROW_ROUTE = Regex("""\b([A-Z]{3})\s*(?:->|→|—|–|-)\s*([A-Z]{3})\b""")
            val CITY_ROUTE = Regex("""\b([A-Z]{4,15}(?:[ ][A-Z]{2,15})?)[ ]+TO[ ]+([A-Z]{4,15}(?:[ ][A-Z]{2,15})?)\b""")
            val LABELED_DATE =
                Regex(
                    """(?:TRAVEL[ ]+DATE|DEPARTURE[ ]+DATE|DATE[ ]+OF[ ]+(?:TRAVEL|JOURNEY)|DATE|DOJ)\s*[:\-]\s*""" +
                        """(\d{1,2}\s*[-/. ]?\s*[A-Z]{3,9}\s*[-/. ,]?\s*\d{0,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})""",
                )
            val LABELED_CABIN =
                Regex("""(?:CABIN[ ]+CLASS|TRAVEL[ ]+CLASS|CABIN|CLASS)\s*[:\-]\s*(PREMIUM[ ]+ECONOMY|ECONOMY|BUSINESS|FIRST)\b""")
            val BARE_CABIN = Regex("""\b(PREMIUM[ ]+ECONOMY|ECONOMY|BUSINESS[ ]+CLASS|FIRST[ ]+CLASS)\b""")
            val LABELED_SEAT = Regex("""SEAT\s*(?:NO\.?)?\s*[:\-]\s*(\d{1,3}[A-K])\b""")
        }
    }
