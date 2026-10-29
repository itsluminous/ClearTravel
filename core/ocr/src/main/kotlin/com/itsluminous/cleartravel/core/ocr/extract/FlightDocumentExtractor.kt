package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.extract.FlightSegments.flightKey
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight
import com.itsluminous.cleartravel.core.ocr.model.ExtractedPassenger
import com.itsluminous.cleartravel.core.ocr.pipeline.OcrLayout

/**
 * The shared engine behind [BookingConfirmationExtractor] and
 * [BoardingPassTextExtractor] (ADR-045): segments the upper-cased OCR row text with
 * [FlightSegmenter], runs the per-field heuristics INSIDE each segment, inherits
 * shared fields (passenger, PNR, cabin) from the preamble, attaches seat-table rows
 * from the tail by route, and dedupes the segments by the ADR-025 identity
 * (carrier + number + date — a later mention of the same flight without a date, e.g.
 * a baggage table, is folded into the dated segment). A document with exactly ONE
 * segment additionally falls back to whole-document extraction for its blank fields,
 * which keeps every pre-ADR-045 single-flight fixture at its recorded values.
 *
 * ADR-047: a segment carries a LIST of passengers. The segment's own text names at
 * most one person per labeled match (a passenger TABLE under a `Name …` header names
 * every row); EVERY seat-table row flying the segment's route attaches as a passenger
 * (a row naming someone already there fills their seat); and the dedupe merges the
 * passengers of a second mention — a stacked page with two passes for the same flight
 * is one segment with two passengers.
 *
 * Confidence ladder (ADR-009): labeled → HIGH, structural → MEDIUM, weak → LOW.
 */
internal class FlightDocumentExtractor(
    private val profile: Profile,
    private val tokens: FlightTokens = FlightTokens(),
) {
    /** What differs between the two document kinds. */
    enum class Profile {
        /** E-tickets: hotel/fare sections stop segments; seats only when labeled. */
        CONFIRMATION,

        /** Boarding passes: bare seats (`12A`) count at LOW; sequence numbers are read. */
        BOARDING_PASS,
    }

    /**
     * Confirmations require the label's separator (`Traveller: …`) — their passenger
     * tables print `Name  Seat  Meal` headers that would otherwise read as a name;
     * boarding passes keep it optional (`PASSENGER SHARMA/RAHUL`).
     */
    private val labeledName: Regex =
        Regex(
            NAME_LABELS +
                (if (profile == Profile.CONFIRMATION) NAME_SEPARATOR_REQUIRED else NAME_SEPARATOR_OPTIONAL) +
                NAME_VALUE,
        )

    private val segmenter =
        when (profile) {
            Profile.CONFIRMATION ->
                FlightSegmenter(tokens, FlightSegmenter.CONFIRMATION_HEADERS, FlightSegmenter.CONFIRMATION_STOPS)
            Profile.BOARDING_PASS ->
                FlightSegmenter(tokens, FlightSegmenter.PASS_HEADERS, FlightSegmenter.PASS_STOPS)
        }

    fun extract(text: String): List<ExtractedFlight> {
        val upper = FlightDocumentPages.withoutProsePages(text).uppercase()
        val segmentation = segmenter.segment(upper)
        val preambleText = segmentation.preamble.joinToString("\n")
        // Passenger tables (`Passenger Information` blocks, `Name  Seat  Meal` tables)
        // list the booking's travellers, wherever the page break put them: their names
        // are shared by every segment, like the preamble's (ADR-047).
        val tableNames =
            (listOf(segmentation.preamble) + segmentation.segments + listOf(segmentation.tail))
                .flatMap { lines -> tableNames(lines.joinToString("\n")) }
                .distinctBy { it.value }
        val shared = extractFields(preambleText).withTableNames(tableNames)

        val segments =
            if (segmentation.segments.isEmpty()) {
                // No flight token anywhere: the whole document is one (possibly empty) segment.
                listOf(extractFields(upper).withTableNames(tableNames))
            } else {
                segmentation.segments
                    .map { lines -> extractFields(lines.joinToString("\n")) }
                    .dropIdentityOnlyNoise()
                    .map { it.inheritShared(shared) }
            }
        val deduped = FlightSegments.dedupe(segments).settleLocatorReadings(upper)
        val attached = attachSeatTable(deduped, segmentation.tail)
        val completed =
            if (attached.size == 1 && segmentation.segments.isNotEmpty()) {
                // Legacy parity: a single-flight document reads blank fields from the
                // whole document minus the tail (the tail never carries flight data).
                // Several mentions folded into one flight (ADR-047: a stacked page with a
                // pass per traveller) read from the FIRST mention only — the repeats
                // would otherwise pair one `TERMINAL T1` with its own echo.
                val mentions = if (segmentation.segments.size == 1) segmentation.segments else segmentation.segments.take(1)
                val body = (segmentation.preamble + mentions.flatten()).joinToString("\n")
                listOf(attached.single().fillBlanksFrom(extractFields(body)))
            } else {
                attached
            }
        return completed.filterNot { it.isEmpty }
    }

    /**
     * In a multi-flight document a segment that carries NOTHING but a carrier + number
     * (no date, no route, no PNR, no seat) is a stray token — an OCR'd currency sign
     * before an amount, a fare-table cell, an example flight quoted by the fine print —
     * not a flight. Only MEDIUM-or-better evidence counts: a LOW city pair (`policies
     * to ensure`) or a LOW bare locator is exactly what prose produces around such a
     * token. Judged on the segment's OWN text, before the shared header fields are
     * inherited (every segment would otherwise carry the booking's locator). A later
     * mention of a flight that IS informative elsewhere is kept so the dedupe can fold
     * its times, terminals or cabin in. A document with a single segment keeps it so a
     * sparse one-liner still prefills the form.
     */
    private fun List<ExtractedFlight>.dropIdentityOnlyNoise(): List<ExtractedFlight> {
        if (size <= 1) return this

        fun ExtractedField.solid() = isPresent && confidence != ExtractionConfidence.LOW

        fun ExtractedFlight.informative() =
            flightDate.isPresent || fromAirport.solid() || toAirport.solid() || seat.isPresent || pnr.solid()
        val informativeKeys = filter { it.informative() }.mapNotNull { it.flightKey() }.toSet()
        val kept = filter { it.informative() || it.flightKey() in informativeKeys }
        return if (kept.isEmpty()) this else kept
    }

    // ---- per-text field extraction ------------------------------------------------

    /**
     * A booking's locator is printed in the header of EVERY page, and OCR reads it a
     * little differently each time (`0C9ISJ` on one page, `OC9ISJ` on the next —
     * O/0, I/1, S/5, B/8 are the usual confusions). When the labeled readings across
     * the document disagree only by such look-alike characters, every segment takes
     * the reading printed MOST OFTEN; two genuinely different locators (an OTA
     * itinerary with an airline PNR per leg) never influence each other, and a single
     * reading is kept as it was read — characters are never "corrected" on their own.
     */
    private fun List<ExtractedFlight>.settleLocatorReadings(upper: String): List<ExtractedFlight> {
        val counts =
            (LABELED_AIRLINE_PNR.findAll(upper) + BARE_LABELED_PNR.findAll(upper))
                .map { it.groupValues[1] }
                .groupingBy { it }
                .eachCount()
        if (counts.size < 2) return this
        return map { flight ->
            val value = flight.pnr.value ?: return@map flight
            val own = counts[value] ?: 0
            val majority =
                counts.entries
                    .filter { (reading, count) -> count > own && lookAlike(reading, value) }
                    .maxByOrNull { it.value } ?: return@map flight
            flight.copy(pnr = flight.pnr.copy(value = majority.key))
        }
    }

    /** Same length and, at every position, the same character or two from one OCR confusion set. */
    private fun lookAlike(
        a: String,
        b: String,
    ): Boolean =
        a.length == b.length &&
            a.indices.all { i ->
                a[i] == b[i] || OCR_CONFUSIONS.any { set -> a[i] in set && b[i] in set }
            }

    private fun extractFields(text: String): ExtractedFlight {
        if (text.isBlank()) return ExtractedFlight.EMPTY
        val lines = text.lines()
        val token = tokens.first(text)
        val route = extractRoute(text)
        val times = extractTimes(text)
        val terminals = extractTerminals(lines, route)
        return ExtractedFlight(
            pnr = extractPnr(text),
            carrier = token?.let { ExtractedField.of(it.carrier, it.confidence) } ?: ExtractedField.EMPTY,
            flightNumber = token?.let { ExtractedField.of(it.number, it.confidence) } ?: ExtractedField.EMPTY,
            fromAirport = route.from,
            toAirport = route.to,
            flightDate = extractDate(text, lines),
            depTime = times.first,
            arrTime = times.second,
            depTerminal = terminals.first,
            arrTerminal = terminals.second,
            cabinClass = extractCabin(text),
            passengers =
                extractPassengers(
                    names = extractNames(text, lines),
                    seat = extractSeat(lines, token?.carrier),
                    sequenceNumber = if (profile == Profile.BOARDING_PASS) extractSequence(text) else ExtractedField.EMPTY,
                ),
        )
    }

    /**
     * The passengers one text names (ADR-047): the seat and sequence number a text
     * carries belong to the FIRST named person (a pass prints one of each); further
     * names (a passenger table) are name-only until a seat row fills them. No name at
     * all → a nameless passenger when there is at least a seat or a sequence.
     */
    private fun extractPassengers(
        names: List<ExtractedField>,
        seat: ExtractedField,
        sequenceNumber: ExtractedField,
    ): List<ExtractedPassenger> {
        if (names.isEmpty()) return ExtractedPassenger.single(ExtractedField.EMPTY, seat, sequenceNumber)
        return names.mapIndexed { index, name ->
            if (index == 0) {
                ExtractedPassenger(name = name, seat = seat, sequenceNumber = sequenceNumber)
            } else {
                ExtractedPassenger(name = name)
            }
        }
    }

    /**
     * Preamble fields a segment lacks: locator, cabin and — for passengers — the
     * preamble's NAMES (never its seat, which belongs to no particular leg): a segment
     * naming nobody takes every preamble name; a segment with a seat but no name is
     * the preamble's single traveller (pre-ADR-047 parity).
     */
    private fun ExtractedFlight.inheritShared(shared: ExtractedFlight): ExtractedFlight {
        val sharedNames = shared.passengers.filter { it.name.isPresent }.map { ExtractedPassenger(name = it.name) }
        val inherited =
            when {
                passengers.isEmpty() -> sharedNames
                passengers.none { it.name.isPresent } && sharedNames.size == 1 ->
                    passengers.mapIndexed { index, p -> if (index == 0) p.copy(name = sharedNames.single().name) else p }
                else -> passengers
            }
        return copy(
            // A labeled locator in the header beats a bare six-character token found
            // inside the segment (`02HOUR` of a travel-time line); equal confidence
            // keeps the segment's own (a per-leg airline PNR next to an OTA booking id).
            pnr = if (shared.pnr.isPresent && shared.pnr.confidence < pnr.confidence) shared.pnr else pnr.orElse(shared.pnr),
            cabinClass = cabinClass.orElse(shared.cabinClass),
            passengers = inherited,
        )
    }

    /** The preamble's fields with [names] (document-level passenger tables) appended to its passengers. */
    private fun ExtractedFlight.withTableNames(names: List<ExtractedField>): ExtractedFlight {
        if (names.isEmpty()) return this
        val incoming = names.map { ExtractedPassenger(name = it) }
        return copy(passengers = ExtractedPassenger.merge(passengers, incoming))
    }

    /**
     * The names a text prints, in order: a labeled passenger (`Traveller: …`) or a BCBP
     * style `SHARMA/RAHUL` names ONE person; a `Name  Seat  Meal  Status` table header
     * names every contiguous row below it whose first cell is a plain name (OTA
     * passenger tables, ADR-047) — falling back to the first such row anywhere below
     * when none follows directly.
     */
    private fun extractNames(
        text: String,
        lines: List<String>,
    ): List<ExtractedField> {
        labeledName.find(text)?.let {
            return listOf(ExtractedField.of(displayName(it.groupValues[1]), ExtractionConfidence.HIGH))
        }
        // A BCBP-style `SHARMA/RAHUL MR` fills its cell on its own; `PNR/BOOKING
        // REFERENCE …` and `CHECK-IN/BAG DROP …` never do.
        lines.asSequence().flatMap { it.cells() }.firstNotNullOfOrNull { SLASH_NAME_CELL.matchEntire(it) }?.let {
            return listOf(ExtractedField.of(displayName(it.groupValues[1]), ExtractionConfidence.MEDIUM))
        }
        return emptyList()
    }

    /**
     * The names a passenger table prints (ADR-047), in order — these describe the
     * BOOKING, so [extract] shares them with every segment. Two layouts:
     *
     * - a `Name  Seat  Meal  Status` row header: every contiguous row below whose first
     *   cell is a plain name (falling back to the first such row anywhere below);
     * - a `Passenger Information` / `Traveller Details` block header on a row of its
     *   own: every contiguous row below that OPENS with a name — the leading run of
     *   words before the passenger's type, gender or title (`hari shankar singh Adult |
     *   Male`), read across cell breaks because OCR splits long names.
     */
    private fun tableNames(text: String): List<ExtractedField> {
        val lines = text.lines()
        val rowHeader = lines.indexOfFirst { it.startsWith("NAME") && it.cells().size >= 2 }
        val blockHeader = lines.indexOfFirst { PASSENGER_BLOCK_HEADER.matches(it) }
        val header = listOf(rowHeader, blockHeader).filter { it >= 0 }.minOrNull() ?: return emptyList()
        val nameOf: (String) -> String? =
            if (header == rowHeader) {
                { line ->
                    val cells = line.cells()
                    cells.firstOrNull()?.takeIf { cells.size >= 2 && PLAIN_NAME.matches(it) }
                }
            } else {
                ::leadingName
            }
        val below = lines.drop(header + 1)
        val contiguous =
            below
                .asSequence()
                .map(nameOf)
                .takeWhile { it != null }
                .filterNotNull()
                .toList()
        val names =
            contiguous.ifEmpty {
                if (header == rowHeader) listOfNotNull(below.firstNotNullOfOrNull(nameOf)) else emptyList()
            }
        return names.distinct().map { ExtractedField.of(it, ExtractionConfidence.MEDIUM) }
    }

    /**
     * `HARI SHANKAR SINGH ADULT | MALE|` → `HARI SHANKAR SINGH`: a leading title is
     * dropped, then two to four alphabetic words up to the first non-name word (a
     * passenger type, gender, status, label or anything with digits/punctuation).
     */
    private fun leadingName(line: String): String? {
        val words =
            line
                .cells()
                .joinToString(" ")
                .split(' ')
                .filter { it.isNotEmpty() }
        val body = words.dropWhile { it.trimEnd('.') in TITLES }
        val run = body.takeWhile { NAME_WORD.matches(it) && it !in PASSENGER_ROW_STOPWORDS }
        return run.takeIf { it.size in 2..4 }?.joinToString(" ")
    }

    /** `MR ARJUN NAIR` -> `ARJUN NAIR`; `SHARMA/RAHUL MR` -> `RAHUL SHARMA`. */
    private fun displayName(raw: String): String {
        val trimmed = raw.trim()
        val slash = trimmed.indexOf('/')
        if (slash < 0) {
            return trimmed
                .split(' ')
                .filter { it.isNotEmpty() }
                .filterIndexed { index, part -> !(index == 0 && part.trimEnd('.') in TITLES) }
                .joinToString(" ")
        }
        val last = trimmed.substring(0, slash).trim()
        val first =
            trimmed
                .substring(slash + 1)
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() && it.trimEnd('.') !in TITLES }
                .joinToString(" ")
        return listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun extractPnr(text: String): ExtractedField {
        LABELED_AIRLINE_PNR.find(text)?.let {
            return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
        }
        // Unlabeled-by-separator `PNR R87T5G`, `PNR/Booking Reference OC9ISJ`: the value
        // needs a digit or must end its cell, so `PNR STATUS` / `BOOKING REFERENCE
        // NUMBER:` never match.
        BARE_LABELED_PNR.find(text)?.let {
            return ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH)
        }
        // OTA references (MakeMyTrip booking ids, Cleartrip trip ids) are real booking
        // handles but not the airline record locator -> MEDIUM.
        LABELED_OTA_REF.find(text)?.let {
            return ExtractedField.of(it.groupValues[1].replace(" ", ""), ExtractionConfidence.MEDIUM)
        }
        BARE_PNR.find(text)?.let {
            return ExtractedField.of(it.groupValues[1], ExtractionConfidence.LOW)
        }
        return ExtractedField.EMPTY
    }

    private data class Route(
        val from: ExtractedField,
        val to: ExtractedField,
    )

    private fun extractRoute(text: String): Route {
        LABELED_ROUTE.find(text)?.let { m ->
            return Route(field(m.groupValues[1], ExtractionConfidence.HIGH), field(m.groupValues[2], ExtractionConfidence.HIGH))
        }
        // `NEW DELHI (DEL) → MUMBAI (BOM)` — parenthesized IATA pair.
        PAREN_ROUTE.find(text)?.let { m ->
            return Route(field(m.groupValues[1], ExtractionConfidence.MEDIUM), field(m.groupValues[2], ExtractionConfidence.MEDIUM))
        }
        // `PAT-Jay Prakash Narayan International Airport  HYD-Rajiv Gandhi International
        // Airport` — one row, a `CODE-Airport name` cell per end (airline e-tickets).
        codeNamePair(text)?.let { return it }
        ARROW_ROUTE.find(text)?.let { m ->
            if (m.groupValues[1] !in iataStopwords && m.groupValues[2] !in iataStopwords) {
                return Route(field(m.groupValues[1], ExtractionConfidence.MEDIUM), field(m.groupValues[2], ExtractionConfidence.MEDIUM))
            }
        }
        // `BLR 10:30 … 13:05 LKO` / `BLR 10:30  To  LKO 13:05` — the itinerary time block.
        val stops = timeStops(text)
        if (stops.size >= 2) {
            return Route(field(stops[0].airport, ExtractionConfidence.MEDIUM), field(stops[1].airport, ExtractionConfidence.MEDIUM))
        }
        // City names without printed IATA codes: keep them (the user reviews the form
        // either way) at LOW confidence.
        CITY_ARROW_ROUTE.find(text)?.let { m ->
            return Route(field(m.groupValues[1], ExtractionConfidence.LOW), field(m.groupValues[2], ExtractionConfidence.LOW))
        }
        CITY_ROUTE.find(text)?.let { m ->
            return Route(field(m.groupValues[1], ExtractionConfidence.LOW), field(m.groupValues[2], ExtractionConfidence.LOW))
        }
        return Route(ExtractedField.EMPTY, ExtractedField.EMPTY)
    }

    /** The first row holding two `CODE-Airport name` cells, read as departure then arrival. */
    private fun codeNamePair(text: String): Route? {
        for (line in text.lines()) {
            val codes =
                line
                    .cells()
                    .mapNotNull { CODE_NAME_CELL.matchEntire(it)?.groupValues?.get(1) }
                    .filter { it !in iataStopwords }
            if (codes.size >= 2 && codes[0] != codes[1]) {
                return Route(field(codes[0], ExtractionConfidence.MEDIUM), field(codes[1], ExtractionConfidence.MEDIUM))
            }
        }
        return null
    }

    private data class TimeStop(
        val airport: String,
        val time: String,
    )

    /** `(airport, HH:mm)` pairs of the time block in document order, stop-words excluded. */
    private fun timeStops(text: String): List<TimeStop> =
        TIME_BLOCK
            .findAll(text)
            .mapNotNull { m ->
                val airport = m.groupValues[1].ifEmpty { m.groupValues[4] }
                val time = m.groupValues[2].ifEmpty { m.groupValues[3] }
                if (airport in iataStopwords) null else normalizeTime(time)?.let { TimeStop(airport, it) }
            }.toList()

    private fun extractTimes(text: String): Pair<ExtractedField, ExtractedField> {
        var dep = LABELED_DEP_TIME.find(text)?.let { normalizeTime(it.groupValues[1]) }?.let { field(it, ExtractionConfidence.HIGH) }
        var arr = LABELED_ARR_TIME.find(text)?.let { normalizeTime(it.groupValues[1]) }?.let { field(it, ExtractionConfidence.HIGH) }
        val stops = timeStops(text)
        if (dep == null && stops.isNotEmpty()) dep = field(stops[0].time, ExtractionConfidence.MEDIUM)
        if (arr == null && stops.size >= 2) arr = field(stops[1].time, ExtractionConfidence.MEDIUM)
        if (dep == null && arr == null) {
            // `12:50, 10 Nov 2026  15:00, 10 Nov 2026` — a row of two dated clock times
            // under the departure and arrival columns.
            val dated = datedTimes(text)
            if (dated.size >= 2) {
                dep = field(dated[0], ExtractionConfidence.MEDIUM)
                arr = field(dated[1], ExtractionConfidence.MEDIUM)
            }
        }
        return (dep ?: ExtractedField.EMPTY) to (arr ?: ExtractedField.EMPTY)
    }

    /** The clock times of the first row that prints at least two `time, date` / `date, time` stamps. */
    private fun datedTimes(text: String): List<String> {
        for (line in text.lines()) {
            val times =
                DATED_TIME
                    .findAll(line)
                    .mapNotNull { normalizeTime(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
                    .toList()
            if (times.size >= 2) return times
        }
        return emptyList()
    }

    /** `8:30`, `08:30 AM`, `1:05 PM` -> `HH:mm`; null when not a clock time. */
    private fun normalizeTime(raw: String): String? {
        val m = CLOCK.matchEntire(raw.trim().uppercase()) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: return null
        val meridiem = m.groupValues[3]
        if (meridiem == "PM" && hour < 12) hour += 12
        if (meridiem == "AM" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    private data class TerminalMention(
        val value: String,
        val row: Int,
        val cell: Int,
        val contextBefore: String,
    )

    /**
     * Departure/arrival terminals. Context decides when it can (`Departure … Terminal 3`,
     * the arrival airport on the same row before the mention); otherwise the row
     * geometry does: a mention in the FIRST cell of its row sits under the departure
     * column, a later cell under the arrival column (`Terminal - T2, …  Terminal - T3, …`
     * on one row; `Economy (L)  Terminal:3` / `Terminal: 2` on stacked rows).
     */
    private fun extractTerminals(
        lines: List<String>,
        route: Route,
    ): Pair<ExtractedField, ExtractedField> {
        val mentions =
            lines.flatMapIndexed { row, line ->
                var offset = 0
                line.cells().flatMapIndexed { cell, cellText ->
                    val start = line.indexOf(cellText, offset)
                    offset = start + cellText.length
                    TERMINAL
                        .findAll(cellText)
                        .map { m ->
                            TerminalMention(
                                value = m.groupValues[1],
                                row = row,
                                cell = cell,
                                contextBefore = line.substring(0, (start + m.range.first).coerceAtLeast(0)),
                            )
                        }.toList()
                }
            }
        if (mentions.isEmpty()) return ExtractedField.EMPTY to ExtractedField.EMPTY
        var dep: TerminalMention? = null
        var arr: TerminalMention? = null
        val unresolved = mutableListOf<TerminalMention>()
        for (mention in mentions) {
            val context = mention.contextBefore.takeLast(TERMINAL_CONTEXT_CHARS)
            val arrival =
                ARRIVAL_WORD.containsMatchIn(context) ||
                    (route.to.value?.let { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(context) } == true)
            val departure =
                DEPARTURE_WORD.containsMatchIn(context) ||
                    (route.from.value?.let { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(context) } == true)
            when {
                arrival && !departure && arr == null -> arr = mention
                departure && !arrival && dep == null -> dep = mention
                else -> unresolved += mention
            }
        }
        when {
            dep == null && arr == null && unresolved.size >= 2 -> {
                val (a, b) = unresolved.take(2)
                if (a.cell != b.cell) {
                    dep = if (a.cell < b.cell) a else b
                    arr = if (a.cell < b.cell) b else a
                } else {
                    dep = a
                    arr = b
                }
            }
            dep == null && arr == null && unresolved.size == 1 ->
                if (unresolved[0].cell > 0) arr = unresolved[0] else dep = unresolved[0]
            dep == null && unresolved.isNotEmpty() -> dep = unresolved[0]
            arr == null && unresolved.isNotEmpty() -> arr = unresolved[0]
        }
        return (dep?.let { field(it.value, ExtractionConfidence.MEDIUM) } ?: ExtractedField.EMPTY) to
            (arr?.let { field(it.value, ExtractionConfidence.MEDIUM) } ?: ExtractedField.EMPTY)
    }

    private fun extractDate(
        text: String,
        lines: List<String>,
    ): ExtractedField {
        for (m in LABELED_DATE.findAll(text)) {
            OcrDates.parseToIso(m.groupValues[1])?.let { return field(it, ExtractionConfidence.HIGH) }
        }
        // Unlabeled: the first date in the segment. Structural placement (a weekday
        // prefix, or the route header's own line) earns MEDIUM; anywhere else LOW.
        for (line in lines) {
            val iso = OcrDates.parseToIso(line) ?: continue
            val structural =
                WEEKDAY.containsMatchIn(line) || ROUTE_LINE.containsMatchIn(line) || DATED_TIME.findAll(line).count() >= 2
            return field(iso, if (structural) ExtractionConfidence.MEDIUM else ExtractionConfidence.LOW)
        }
        return ExtractedField.EMPTY
    }

    private fun extractCabin(text: String): ExtractedField {
        LABELED_CABIN.find(text)?.let { return field(it.groupValues[1], ExtractionConfidence.HIGH) }
        BARE_CABIN.find(text)?.let { return field(it.groupValues[1].removeSuffix(" CLASS"), ExtractionConfidence.LOW) }
        return ExtractedField.EMPTY
    }

    /**
     * `Seat 12A` / `Seat: 34C` in one cell, or a `Seat` label cell followed by a cell
     * holding nothing but the seat (HIGH); on a boarding pass a bare `12A` (LOW). A
     * column header `Seat  6E Add-ons` is neither: the value cell carries more than a
     * seat, and a bare candidate equal to the document's airline code ([carrier]) or
     * sitting inside a flight token (`6E 432`) is the airline, not row 6 seat E.
     */
    private fun extractSeat(
        lines: List<String>,
        carrier: String?,
    ): ExtractedField {
        for (line in lines) {
            val cells = line.cells()
            cells.forEachIndexed { index, cell ->
                LABELED_SEAT_IN_CELL.find(cell)?.let { return field(it.groupValues[1], ExtractionConfidence.HIGH) }
                if (SEAT_LABEL_CELL.matches(cell)) {
                    cells.getOrNull(index + 1)?.let { next ->
                        SEAT_VALUE_CELL.matchEntire(next)?.let { return field(it.groupValues[1], ExtractionConfidence.HIGH) }
                    }
                }
            }
        }
        if (profile == Profile.BOARDING_PASS) {
            for (line in lines) {
                val tokenRanges = tokens.findAll(line).map { it.range }
                BARE_SEAT
                    .findAll(line)
                    .firstOrNull { m ->
                        m.groupValues[1] != carrier && tokenRanges.none { it.first <= m.range.last && m.range.first <= it.last }
                    }?.let { return field(it.groupValues[1], ExtractionConfidence.LOW) }
            }
        }
        return ExtractedField.EMPTY
    }

    private fun extractSequence(text: String): ExtractedField =
        LABELED_SEQ.find(text)?.let { field(it.groupValues[1].trimStart('0'), ExtractionConfidence.HIGH) } ?: ExtractedField.EMPTY

    // ---- cross-segment steps ---------------------------------------------------

    /**
     * Seat-table rows in the tail (`Prakash Kumar  BLR-LKO  12A  INR 0.00`) attach to the
     * segment flying that route — EVERY row (ADR-047), each a passenger: a row naming
     * someone already on the segment fills their seat (the shared [ExtractedPassenger.merge]
     * rule); a NAMELESS row fills the first seat-less passenger before it counts as a
     * new person (a seat table lists one row per traveller); duplicate rows (the same
     * name and seat printed twice) attach once. The segment's own passenger stays #1.
     */
    private fun attachSeatTable(
        segments: List<ExtractedFlight>,
        tail: List<String>,
    ): List<ExtractedFlight> {
        if (tail.isEmpty()) return segments
        val rows =
            tail
                .mapNotNull { line ->
                    val m = SEAT_TABLE_ROW.find(line) ?: return@mapNotNull null
                    val name = line.cells().firstOrNull()?.takeIf { PLAIN_NAME.matches(it) }
                    SeatRow(from = m.groupValues[1], to = m.groupValues[2], seat = m.groupValues[3], name = name)
                }.distinct()
        if (rows.isEmpty()) return segments
        return segments.map { segment ->
            val matching = rows.filter { it.from == segment.fromAirport.value && it.to == segment.toAirport.value }
            if (matching.isEmpty()) return@map segment
            val passengers = segment.passengers.toMutableList()
            for (row in matching) {
                val incoming =
                    ExtractedPassenger(
                        name = row.name?.let { field(it, ExtractionConfidence.MEDIUM) } ?: ExtractedField.EMPTY,
                        seat = field(row.seat, ExtractionConfidence.MEDIUM),
                    )
                val seatless = if (row.name == null) passengers.indexOfFirst { !it.seat.isPresent } else -1
                when {
                    seatless >= 0 -> passengers[seatless] = passengers[seatless].fillBlanksFrom(incoming)
                    else -> {
                        val merged = ExtractedPassenger.merge(passengers, listOf(incoming))
                        passengers.clear()
                        passengers += merged
                    }
                }
            }
            segment.copy(passengers = passengers)
        }
    }

    private data class SeatRow(
        val from: String,
        val to: String,
        val seat: String,
        val name: String?,
    )

    // ---- helpers -------------------------------------------------------------------

    private val iataStopwords: Set<String> =
        tokens.rules.iataStopwords
            .map { it.uppercase() }
            .toSet()

    private fun field(
        value: String,
        confidence: ExtractionConfidence,
    ): ExtractedField = ExtractedField.of(value.trim(), confidence)

    private fun ExtractedField.orElse(fallback: ExtractedField): ExtractedField = if (isPresent) this else fallback

    private fun String.cells(): List<String> = split(OcrLayout.CELL_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        val TITLES = setOf("MR", "MRS", "MS", "MSTR", "MISS", "DR")
        const val NAME_LABELS = """(?:NAME[ ]+OF[ ]+PASSENGER|PASSENGER(?:\(S\))?(?:[ ]+NAME)?|TRAVELLERS?|GUEST|NAME)"""
        const val NAME_SEPARATOR_REQUIRED = """[ ]*\d?\.?[ ]*[:\-][ ]*"""
        const val NAME_SEPARATOR_OPTIONAL = """[ ]*\d?\.?[ ]*[:\-]?[ ]*"""
        const val NAME_VALUE =
            """((?:MR|MRS|MS|MSTR|MISS|DR)\.?[ ]+[A-Z]+(?:[ ]+[A-Z]+)*|""" +
                """[A-Z]{2,25}/[A-Z]{2,25}(?:[ ]+[A-Z]{1,10})?|[A-Z]+(?:[ ]+[A-Z]+)+)"""

        /** `SHARMA/RAHUL MR` filling a whole cell — never `PNR/BOOKING REFERENCE …` or a URL path. */
        val SLASH_NAME_CELL = Regex("""([A-Z]{2,25}/[A-Z]{2,25}(?:[ ]+MRS?|[ ]+MS)?)""")
        val PLAIN_NAME = Regex("""[A-Z]{2,}(?: [A-Z]{2,}){1,3}""")
        val NAME_WORD = Regex("""[A-Z]{2,}""")

        /** `Passenger Information`, `Traveller Details`, `Passengers` — a block header on a row of its own. */
        val PASSENGER_BLOCK_HEADER =
            Regex("""(?:PASSENGER|TRAVELLER|TRAVELER|GUEST)S?(?:[ ]+(?:INFORMATION|DETAILS?|NAMES?|LIST))?[ ]*:?""")

        /** Words that end a passenger row's name: type, gender, status, column labels. */
        val PASSENGER_ROW_STOPWORDS =
            setOf(
                "ADULT",
                "CHILD",
                "INFANT",
                "MALE",
                "FEMALE",
                "CONFIRMED",
                "CANCELLED",
                "WAITLISTED",
                "SEAT",
                "MEAL",
                "STATUS",
                "SECTOR",
                "TICKET",
                "PNR",
                "AGE",
                "GENDER",
                "TYPE",
                "YRS",
                "YEARS",
            )
        const val PNR_LABELS = """(?:AIRLINE[ ]+PNR|PNR|BOOKING[ ]*REF(?:ERENCE)?|CONFIRMATION|RECORD[ ]*LOCATOR)"""
        const val PNR_LABEL_CHAIN = PNR_LABELS + """(?:\s*/\s*""" + PNR_LABELS + """)?\s*\)?\s*(?:NO\.?|NUMBER)?\s*\(?(?:PNR)?\)?"""
        val LABELED_AIRLINE_PNR = Regex(PNR_LABEL_CHAIN + """\s*[:#\-]\s*([A-Z0-9]{6})\b""")

        /** Whitespace-separated label: the value carries a digit or ends its cell (`BOOKING REFERENCE NUMBER:` never qualifies). */
        val BARE_LABELED_PNR = Regex("""\b""" + PNR_LABEL_CHAIN + """\s((?=[A-Z0-9]*\d)[A-Z0-9]{6}|[A-Z]{6}(?=\s\s|\s*$))\b""")

        /** Characters OCR swaps for one another in upper-case alphanumerics. */
        val OCR_CONFUSIONS = listOf("O0QD", "I1L", "S5", "B8", "Z2", "G6")
        val LABELED_OTA_REF =
            Regex("""(?:BOOKING[ ]+ID|TRIP[ ]+ID|ORDER[ ]+ID|REFERENCE[ ]+ID)\s*[:#\-]\s*([A-Z]{0,4}\s?-?\s?\d{6,16})\b""")

        /** Six alphanumerics mixing letters and digits — but not a quantity (`02HOUR`, `10MINS`, `100KGS`). */
        val BARE_PNR =
            Regex("""\b(?!\d{1,3}(?:HOUR|HOURS|HRS|HR|MIN|MINS|KGS|KG|DAYS|PCS)\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*\d)([A-Z0-9]{6})\b""")
        val LABELED_ROUTE =
            Regex("""FROM\s*[:\-]?\s*[A-Z ]*\(?([A-Z]{3})\)?\s+TO\s*[:\-]?\s*[A-Z ]*\(?([A-Z]{3})\)?""")
        val PAREN_ROUTE =
            Regex("""\(([A-Z]{3})\)\s*(?:->|→|—|–|-|\bTO\b)\s*[A-Z ]*\(([A-Z]{3})\)""")
        val ARROW_ROUTE = Regex("""\b([A-Z]{3})\s*(?:->|→|—|–|-)\s*([A-Z]{3})\b${FlightSegmenter.CODE_PAIR_GUARD}""")

        /** `PAT-Jay Prakash Narayan International Airport` — a code and the airport's name in one cell. */
        val CODE_NAME_CELL = Regex("""([A-Z]{3})\s*[-–—:]\s*[A-Z][A-Z .,'()&]{3,}""")

        /** `10 Nov 2026`, `10-11-2026` — the date half of a [DATED_TIME] stamp (non-capturing). */
        const val STAMP_DATE = """(?:\d{1,2}\s*[-/. ]?\s*[A-Z]{3,9}\s*[-/. ,]?\s*\d{2,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})"""

        /** `12:50, 10 Nov 2026` or `10 Nov 2026, 12:50` — a clock time stamped with its date. */
        val DATED_TIME =
            Regex(
                """\b(\d{1,2}:\d{2}(?:\s*[AP]M)?)\s*[,.]?\s*$STAMP_DATE\b""" +
                    """|\b$STAMP_DATE\s*[,.]?\s*(\d{1,2}:\d{2}(?:\s*[AP]M)?)\b""",
            )
        val CITY_ARROW_ROUTE = Regex("""\b([A-Z]{4,15})\s*(?:->|→|—|–|\+|\s-\s)\s*([A-Z]{4,15})\b""")
        val CITY_ROUTE = Regex("""\b([A-Z]{4,15}(?:[ ][A-Z]{2,15})?)[ ]+TO[ ]+([A-Z]{4,15}(?:[ ][A-Z]{2,15})?)\b""")
        val ROUTE_LINE = Regex("""\b[A-Z]{3,}\s*(?:→|->|—|–|\+|\s-\s)\s*[A-Z]{3,}\b|\([A-Z]{3}\)""")
        val TIME_BLOCK =
            Regex(
                """\b([A-Z]{3})\s+(\d{1,2}:\d{2}(?:\s*[AP]M)?)\b""" +
                    """|\b(\d{1,2}:\d{2}(?:\s*[AP]M)?)\s+([A-Z]{3})\b""",
            )
        val LABELED_DEP_TIME =
            Regex("""\b(?:DEPARTURE|DEPARTS?|DEP|DEPARTING|STD)\s*(?:TIME)?\s*[:\-]?\s*(\d{1,2}:\d{2}(?:\s*[AP]M)?)\b""")
        val LABELED_ARR_TIME =
            Regex("""\b(?:ARRIVAL|ARRIVES?|ARR|ARRIVING|STA)\s*(?:TIME)?\s*[:\-]?\s*(\d{1,2}:\d{2}(?:\s*[AP]M)?)\b""")
        val CLOCK = Regex("""(\d{1,2}):(\d{2})\s*([AP]M)?""")
        val TERMINAL = Regex("""TERMINAL\s*[-:]?\s*(?:T\s*)?(\d{1,2}[A-Z]?|[A-Z])\b""")
        val ARRIVAL_WORD = Regex("""ARRIV""")
        val DEPARTURE_WORD = Regex("""DEPART""")
        const val TERMINAL_CONTEXT_CHARS = 40
        val LABELED_DATE =
            Regex(
                """(?:TRAVEL[ ]+DATE|DEPARTURE[ ]+DATE|DATE[ ]+OF[ ]+(?:TRAVEL|JOURNEY)|DATE|DOJ)\s*[:\-]\s*""" +
                    """(\d{1,2}\s*[-/. ]?\s*[A-Z]{3,9}\s*[-/. ,]?\s*\d{0,4}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})""",
            )
        val WEEKDAY = Regex("""\b(?:MON|TUE|WED|THU|FRI|SAT|SUN)[A-Z]*,?\s+\d{1,2}\b""")
        val LABELED_CABIN =
            Regex("""(?:CABIN[ ]+CLASS|TRAVEL[ ]+CLASS|CABIN|CLASS)\s*[:\-]\s*(PREMIUM[ ]+ECONOMY|ECONOMY|BUSINESS|FIRST)\b""")
        val BARE_CABIN = Regex("""\b(PREMIUM[ ]+ECONOMY|ECONOMY|BUSINESS[ ]+CLASS|FIRST[ ]+CLASS)\b""")
        val LABELED_SEAT_IN_CELL = Regex("""\bSEAT\s*(?:NO\.?)?\s*[:\-]?\s*(\d{1,3}[A-K])\b""")
        val SEAT_LABEL_CELL = Regex("""(?:[A-Z ]+ )?SEAT\s*(?:NO\.?)?\s*[:\-]?""")
        val SEAT_VALUE_CELL = Regex("""(\d{1,3}[A-K])[.,;]?""")
        val BARE_SEAT = Regex("""\b(\d{1,2}[A-F])\b""")
        val LABELED_SEQ = Regex("""SEQ(?:UENCE)?\s*(?:NO\.?)?\s*[:\-]?\s*(\d{1,5})\b""")
        val SEAT_TABLE_ROW = Regex("""\b([A-Z]{3})\s*(?:-|–|—|->|→)\s*([A-Z]{3})\b.*?\b(\d{1,3}[A-K])\b""")
    }
}
