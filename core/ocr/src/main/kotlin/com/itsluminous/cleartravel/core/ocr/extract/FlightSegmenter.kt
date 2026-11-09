package com.itsluminous.cleartravel.core.ocr.extract

/**
 * Splits upper-cased OCR row text into per-flight segments (ADR-045). Shared by the
 * booking-confirmation and boarding-pass extractors; the field extractors then run
 * INSIDE each segment so a return leg's date never overwrites the onward leg's.
 *
 * Rules, applied line by line:
 *
 * - **Preamble** — everything before the first segment (booking id, passenger, a
 *   shared PNR); fields missing from a segment are inherited from it.
 * - A segment OPENS at a **strong boundary**: a flight token that differs from the
 *   current segment's flight, a labeled PNR that differs from the current/last one
 *   (once the segment has a flight), a *repeated* header kind (a second
 *   `ONWARD/RETURN` label, a second `BOARDING PASS`, a second route block once the
 *   current segment already has a flight), or — for the first segment — any header
 *   or flight token. A header kind the current segment has not seen yet merely
 *   extends it, so `Bengaluru → Lucknow` / `BLR 10:30 …` / `Air India Express 2019`
 *   in any order land in ONE segment.
 * - A **stop header** (`Hotel`, `Seat Fare`, `Payment Summary`, hotel-stay rows…)
 *   closes the current segment; lines until the next boundary go to the **tail**,
 *   which is scanned only for seat-table rows, never for dates or routes. A header
 *   inside the tail opens a TENTATIVE segment: it becomes real only if a flight token
 *   follows (the second leg after a hotel block); otherwise its lines return to the
 *   tail (the fare table's `Bengaluru(BLR) to Lucknow(LKO)` rows).
 * - A segment that ends with no flight token of its own is a continuation of the
 *   previous one (a false header) and is merged back into it.
 * - ADR-050: an agency trip view prints each leg as a VERTICAL block — a dated time
 *   row (`11:05 PM • 21 Nov 2026`) over an `(SEA) Seattle-Tacoma …` airport row for the
 *   departure, the same pair for the arrival, then `Airline/Flight #  Alaska Airlines /
 *   AS22`; a `Layover 2h 41m • New York (JFK)` row sits between legs. The dated time row
 *   is a PAIRED header kind: a leg's own two rows extend its segment, and only a third
 *   one, once the segment has its flight token, opens the next leg's segment (the
 *   header's own `AS22` mention may have opened the first segment before the leg).
 */
internal class FlightSegmenter(
    private val tokens: FlightTokens,
    private val headerKinds: List<Regex>,
    private val stopHeaders: Regex,
) {
    /** Header kinds a leg prints TWICE (departure and arrival endpoint rows): a repeat splits only after a pair. */
    private val pairedKinds: Set<Int> = headerKinds.indices.filter { headerKinds[it] === ENDPOINT_TIME_ROW }.toSet()

    private data class OpenSegment(
        val lines: MutableList<String>,
        /** Opened by a header inside the tail: real only once a flight token follows. */
        val tentative: Boolean,
    )

    data class Segmentation(
        val preamble: List<String>,
        val segments: List<List<String>>,
        /** Lines after stop headers: fare/meal/seat tables, hotel blocks. */
        val tail: List<String>,
    )

    fun segment(upperText: String): Segmentation {
        val lines = upperText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val preamble = mutableListOf<String>()
        val segments = mutableListOf<OpenSegment>()
        val tail = mutableListOf<String>()
        var current: MutableList<String>? = null
        var currentTentative = false
        val currentKinds = mutableMapOf<Int, Int>()
        var currentFlight: String? = null
        var currentPnr: String? = null
        var lastPnr: String? = null
        var inTail = false

        fun close() {
            current?.let { if (it.isNotEmpty()) segments += OpenSegment(it, currentTentative) }
            current = null
            currentTentative = false
            currentKinds.clear()
            currentFlight = null
            currentPnr = null
        }

        for ((index, line) in lines.withIndex()) {
            val lineTokens = tokens.findAll(line)
            val kinds = headerKinds.indices.filter { headerKinds[it].containsMatchIn(line) }
            // ADR-050: a row that is nothing but a flight code the document describes
            // again further down (`AS22` in a trip view's header) is a summary mention
            // and stays in the preamble instead of opening the first segment early.
            val summaryMention =
                current == null && !inTail && kinds.isEmpty() && lineTokens.size == 1 &&
                    lineTokens.single().range.let { it.first == 0 && it.last == line.lastIndex } &&
                    lines.drop(index + 1).any { later -> tokens.findAll(later).any { it.key == lineTokens.single().key } }
            val linePnr = LABELED_PNR_IN_LINE.find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            val knownPnr = currentPnr ?: lastPnr
            val distinctFlight = currentFlight != null && lineTokens.any { it.key != currentFlight }
            val distinctPnr = currentFlight != null && linePnr != null && knownPnr != null && linePnr != knownPnr
            // A header kind seen before in this segment — for a PAIRED kind (an endpoint
            // row, ADR-050) only once the segment already holds a complete pair.
            val repeatedHeader =
                currentFlight != null && kinds.any { (currentKinds[it] ?: 0) >= if (it in pairedKinds) 2 else 1 }
            val opensFirst = current == null && !inTail && (kinds.isNotEmpty() || lineTokens.isNotEmpty()) && !summaryMention
            val opensFromTail = inTail && (kinds.isNotEmpty() || lineTokens.isNotEmpty())
            val strong = distinctFlight || distinctPnr || repeatedHeader || opensFirst || opensFromTail
            when {
                stopHeaders.containsMatchIn(line) && !strong -> {
                    close()
                    inTail = true
                    tail += line
                }
                strong -> {
                    close()
                    currentTentative = inTail && lineTokens.isEmpty()
                    inTail = false
                    current = mutableListOf(line)
                    kinds.forEach { currentKinds[it] = 1 }
                    currentFlight = lineTokens.firstOrNull()?.key
                    currentPnr = linePnr
                }
                current != null -> {
                    current!! += line
                    kinds.forEach { currentKinds[it] = (currentKinds[it] ?: 0) + 1 }
                    if (currentFlight == null) currentFlight = lineTokens.firstOrNull()?.key
                    if (currentPnr == null) currentPnr = linePnr
                }
                inTail -> tail += line
                else -> preamble += line
            }
            if (linePnr != null) lastPnr = linePnr
        }
        close()

        // Flightless segments are continuations (false headers): merge them back —
        // unless they were opened from the tail, where they are fare-table rows that
        // belong to the tail (seat attach still sees them).
        val merged = mutableListOf<MutableList<String>>()
        for ((segment, tentative) in segments) {
            val hasFlight = segment.any { tokens.findAll(it).isNotEmpty() }
            when {
                hasFlight || (merged.isEmpty() && !tentative) -> merged += segment
                tentative -> tail += segment
                else -> merged.last() += segment
            }
        }
        // A leading flightless block is preamble, not a segment.
        if (merged.isNotEmpty() && merged.first().none { tokens.findAll(it).isNotEmpty() }) {
            preamble += merged.removeAt(0)
        }
        return Segmentation(preamble = preamble, segments = merged, tail = tail)
    }

    companion object {
        /**
         * `PNR: X3C9GI`, `Airline PNR R87T5G`, `PNR/Booking Reference OC9ISJ` — the
         * per-segment record locator: the label (or a slash-joined pair of labels)
         * followed by a separator and any 6 alphanumerics, or by whitespace and a value
         * that contains a digit or ends its cell, so `PNR STATUS` and
         * `BOOKING REFERENCE NUMBER:` are never locators.
         */
        val LABELED_PNR_IN_LINE =
            Regex(
                """(?:AIRLINE\s+PNR|\bPNR|\bBOOKING\s*REF(?:ERENCE)?|\bRECORD\s*LOCATOR)""" +
                    """(?:\s*/\s*(?:PNR|BOOKING\s*REF(?:ERENCE)?|RECORD\s*LOCATOR))?""" +
                    """\s*\)?\s*(?:NO\.?|NUMBER)?\s*\(?(?:PNR)?\)?\s*""" +
                    """(?:[:#\-]\s*([A-Z0-9]{6})|\s((?=[A-Z0-9]*\d)[A-Z0-9]{6}|[A-Z]{6}(?=\s\s|\s*$)))\b""",
            )

        /**
         * `XXX-YYY` — but not `PAT-Jay Prakash Narayan International Airport`, where the
         * right side opens an airport NAME (three or more unpunctuated words follow);
         * `DEL - GOI Date: …` and `BLR - HYD Travel Date: …` stay pairs.
         */
        const val CODE_PAIR_GUARD = """(?![ ][A-Z]{2,}[ ][A-Z]{2,}[ ][A-Z]{2,})"""

        /** `10 Nov 2026`, `10-11-2026` — the date half of a dated time stamp (non-capturing); the month may open with an OCR'd `0` (`0CT`). */
        const val STAMP_DATE = """(?:\d{1,2}\s*[-/. ]?\s*[A-Z0][A-Z]{2,8}\s*[-/. ,]?\s*\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})"""

        /** `+1d`, `*1d` (OCR'd plus), `+1 day` — the overnight marker after an arrival stamp (ADR-050). */
        const val DAY_MARKER = """(?:\s*[+*]\s*([0-9IL])\s*(?:D|DAYS?))?"""

        /**
         * A row that is ONE clock time stamped with its date and nothing else (ADR-050):
         * `11:05 PM • 21 Nov 2026`, `7:24 AM. 22 Nov 2026+1d`, `11:10 AM 23 Nov 2026*1d` —
         * any of `•`, `·`, `|`, `-`, `,`, `.` or just a space between time and date.
         * Group 1 = time, 2 = date (empty when the row prints only the time, or a time and
         * its `+1d`), 3 = the day marker's count.
         */
        val ENDPOINT_TIME_ROW =
            Regex("""^(\d{1,2}:\d{2}\s*(?:[AP]M)?)\s*[•·|,.\-]?\s*($STAMP_DATE)?$DAY_MARKER\s*$""")

        /** `(SEA) Seattle-Tacoma International Airport` — an airport row of a vertical leg block (ADR-050); group 1 = code. */
        val AIRPORT_ROW = Regex("""^\(([A-Z]{3})\)\s+(?!.*\([A-Z]{3}\))[A-Z][A-Z0-9 .,'()&/\-]*$""")

        private val CITY_ARROW = Regex("""\b[A-Z]{3,}\s*(?:→|->|—|–|\+|\s-\s)\s*[A-Z]{3,}\b""")
        private val PAREN_ROUTE = Regex("""\([A-Z]{3}\)\s*(?:→|->|—|–|-|\bTO\b)\s*[A-Z ]*\([A-Z]{3}\)""")
        private val CODE_PAIR = Regex("""\b[A-Z]{3}\s*(?:->|→|—|–|-)\s*[A-Z]{3}\b$CODE_PAIR_GUARD""")
        private val IATA_TIME_BLOCK = Regex("""\b[A-Z]{3}\s+\d{1,2}:\d{2}\b|\b\d{1,2}:\d{2}\s+[A-Z]{3}\b""")
        private val LEG_LABEL =
            Regex("""^(?:ONWARD|RETURN(?:ING|ED)?|OUTBOUND|INBOUND|DEPARTING)\b|^(?:FLIGHT|SEGMENT|LEG|SECTOR)\s*\d\b""")
        private val PASS_HEADER = Regex("""\bBOARDING\s+PASS\b""")
        private val LABELED_ROUTE = Regex("""\bFROM\s*[:\-]?\s*[A-Z ]*\(?[A-Z]{3}\)?\s+TO\b""")

        /** Header kinds of a booking confirmation (ADR-045): route headers and leg labels. */
        val CONFIRMATION_HEADERS = listOf(CITY_ARROW, PAREN_ROUTE, CODE_PAIR, IATA_TIME_BLOCK, LEG_LABEL, ENDPOINT_TIME_ROW)

        /** Sections that never describe a flight: hotels, fares, meals, T&C. */
        val CONFIRMATION_STOPS =
            Regex(
                """^(?:HOTEL|HOTELS|ITINERARY\s+DETAILS|SEAT\s+FARE|SEAT\s+CHARGES?|MEALS?(?:\s+CHARGES?)?|MEAL\s+PREFERENCE|""" +
                    """GRAND\s+TOTAL|PRICE|FARE\s+(?:BREAKUP|SUMMARY|DETAILS?)|PAYMENT\s+(?:SUMMARY|DETAILS?)|""" +
                    """IMPORTANT\s+INFORMATION|BAGGAGE\s+INFORMATION|CANCELLATION|TERMS|CONTACT\s+INFORMATION|""" +
                    """AIRLINE\s+CONTACT)\b""" +
                    """|\b(?:NUMBER\s+OF\s+ROOMS|STAY\s+PERIOD|CHECK[- ]?OUT|\d+\s+NIGHTS?|ROOMS?\s+SELECTION|RESORT)\b""",
            )

        /** Header kinds of a boarding pass: a repeated pass header or route block opens the next pass. */
        val PASS_HEADERS = listOf(PASS_HEADER, LABELED_ROUTE, CODE_PAIR, IATA_TIME_BLOCK)

        /** Passes carry no hotel/fare sections; only fare footers are cut off. */
        val PASS_STOPS = Regex("""^(?:FARE\s+(?:BREAKUP|SUMMARY|DETAILS?)|PAYMENT\s+SUMMARY|GRAND\s+TOTAL)\b""")
    }
}
