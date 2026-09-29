package com.itsluminous.cleartravel.feature.flights.text

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.extract.OcrDates
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure, rule-driven parser for pasted or shared flight SMS/email text (ADR-042), e.g.
 * `Akasa Air flight QP 1421 with PNR X4F18V from BLR (Terminal 1) to VNS on 29 May 26`.
 *
 * Every regex comes from [AirlineSmsRules] (`assets/airline-sms-rules.json`, ADR-003):
 * the code only decides WHICH list to consult, in what order, and what confidence a
 * hit earns — labeled matches HIGH, structural matches MEDIUM, whole-text fallbacks
 * LOW. Carrier and IATA codes are matched case-sensitively so prose never passes as
 * a code; keyword matching is case-insensitive inside the rules themselves.
 *
 * Structure signal: without a flight number, a labeled PNR, a route or a known
 * airline the text is not about a flight — [FlightTextExtraction.EMPTY] (blank form
 * + "could not read a flight" notice) instead of guessing from a stray date.
 */
@Singleton
class FlightTextParser
    @Inject
    constructor(
        private val ruleSource: AirlineSmsRuleSource,
    ) {
        fun parse(
            text: String,
            today: LocalDate = LocalDate.now(),
        ): FlightTextExtraction = runCatching { parseInternal(text, today) }.getOrDefault(FlightTextExtraction.EMPTY)

        private fun parseInternal(
            raw: String,
            today: LocalDate,
        ): FlightTextExtraction {
            val rules = ruleSource.load()
            val text = normalize(raw)
            if (text.isBlank()) return FlightTextExtraction.EMPTY

            val aliasCarrier = rules.airlineByAlias(text)
            // A flight number's carrier beats the alias (the alias is context, the
            // number is the flight); the alias still selects the airline's patterns.
            val provisional = rules.patternsFor(aliasCarrier)
            val flights = detectFlights(text, rules, provisional)
            val first = flights.firstOrNull()
            val carrierCode = first?.carrier ?: aliasCarrier
            val patterns = rules.patternsFor(carrierCode)

            val pnr = extractPnr(text, rules, patterns, flights)
            val route = extractRoute(text, rules, patterns)
            val hasStructure =
                first != null || pnr.confidence == ExtractionConfidence.HIGH || route.from.isPresent || aliasCarrier != null
            if (!hasStructure) return FlightTextExtraction.EMPTY

            val carrierConfidence = first?.confidence ?: ExtractionConfidence.MEDIUM
            val (depTerminal, arrTerminal) = extractTerminals(text, patterns, route)
            return FlightTextExtraction(
                passengerName = extractPassenger(text, rules, patterns),
                pnr = pnr,
                carrier = ExtractedField.of(carrierCode, carrierConfidence),
                airlineName = ExtractedField.of(carrierCode?.let(rules::airlineName), carrierConfidence),
                flightNumber = ExtractedField.of(first?.number, first?.confidence ?: ExtractionConfidence.NONE),
                fromAirport = route.from,
                toAirport = route.to,
                flightDate = extractDate(text, patterns, today),
                depTime = extractDepTime(text, patterns),
                arrTime = firstTime(text, patterns.arrTime, ExtractionConfidence.HIGH),
                depTerminal = depTerminal,
                arrTerminal = arrTerminal,
                seat = firstGroup(text, patterns.seat, ExtractionConfidence.HIGH),
                cabinClass =
                    firstGroup(text, patterns.cabin, ExtractionConfidence.MEDIUM).takeIf { it.isPresent }
                        ?: firstGroup(text, patterns.bareCabin, ExtractionConfidence.LOW),
                additionalFlights = (flights.size - 1).coerceAtLeast(0),
            )
        }

        private data class FlightToken(
            val carrier: String,
            val number: String,
            val confidence: ExtractionConfidence,
        )

        private data class Route(
            val from: ExtractedField = ExtractedField.EMPTY,
            val to: ExtractedField = ExtractedField.EMPTY,
            /** Index just past the "to" airport in the text; -1 when no route. */
            val toEnd: Int = -1,
        )

        /**
         * Distinct flight segments in text order. Labeled ("flight QP 1421") and bare
         * tokens with a KNOWN carrier ("6E-2001") are HIGH; bare tokens with an unknown
         * two-letter code count only with a 3–4 digit number and score MEDIUM. Stop-words
         * ("ON 29", "AT 06") never count.
         */
        private fun detectFlights(
            text: String,
            rules: AirlineSmsRules,
            patterns: AirlineSmsPatterns,
        ): List<FlightToken> {
            val stop = rules.flightCodeStopwords.toSet()
            val tokens = mutableListOf<FlightToken>()

            fun add(token: FlightToken) {
                if (tokens.none { it.carrier == token.carrier && it.number == token.number }) tokens += token
            }
            for (m in allMatches(text, patterns.labeledFlight)) {
                val code = m.groupValues[1]
                if (code in stop) continue
                add(FlightToken(code, m.groupValues[2].trimStart('0').ifEmpty { "0" }, ExtractionConfidence.HIGH))
            }
            for (m in allMatches(text, patterns.bareFlight)) {
                val code = m.groupValues[1]
                val number = m.groupValues[2]
                if (code in stop) continue
                val known = rules.infoFor(code) != null
                if (!known && number.length < 3) continue
                add(
                    FlightToken(
                        code,
                        number.trimStart('0').ifEmpty { "0" },
                        if (known) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM,
                    ),
                )
            }
            return tokens
        }

        private fun extractPnr(
            text: String,
            rules: AirlineSmsRules,
            patterns: AirlineSmsPatterns,
            flights: List<FlightToken>,
        ): ExtractedField {
            firstGroup(text, patterns.labeledPnr, ExtractionConfidence.HIGH).takeIf { it.isPresent }?.let { return it }
            // Unlabeled 6-char alphanumerics are weak: skip anything that is really a
            // flight ("QP1421"), an IATA stop-word or the airline's own code.
            val flightLike = flights.map { it.carrier + it.number }.toSet()
            for (m in allMatches(text, patterns.barePnr)) {
                val candidate = m.groupValues[1]
                if (candidate in flightLike || FLIGHT_SHAPE.matches(candidate)) continue
                if (rules.infoFor(candidate.take(2)) != null && candidate.drop(2).all(Char::isDigit)) continue
                return ExtractedField.of(candidate, ExtractionConfidence.LOW)
            }
            return ExtractedField.EMPTY
        }

        private fun extractRoute(
            text: String,
            rules: AirlineSmsRules,
            patterns: AirlineSmsPatterns,
        ): Route {
            val stop = rules.iataStopwords.toSet()
            patterns.route.forEachIndexed { index, pattern ->
                for (m in regex(pattern).findAll(text)) {
                    val from = m.groupValues[1]
                    val to = m.groupValues[2]
                    if (from == to || from in stop || to in stop) continue
                    // The first rule is the labeled "from X to Y" form; the rest are structural.
                    val confidence = if (index == 0) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM
                    return Route(
                        from = ExtractedField.of(from, confidence),
                        to = ExtractedField.of(to, confidence),
                        toEnd =
                            m.groups[2]
                                ?.range
                                ?.last
                                ?.plus(1) ?: m.range.last + 1,
                    )
                }
            }
            return Route()
        }

        /** Labeled ("on 29 May 26", "Date of travel: …") HIGH; any date anywhere LOW. */
        private fun extractDate(
            text: String,
            patterns: AirlineSmsPatterns,
            today: LocalDate,
        ): ExtractedField {
            for (m in allMatches(text, patterns.labeledDate)) {
                OcrDates.parseToIso(m.groupValues[1], today)?.let { return ExtractedField.of(it, ExtractionConfidence.HIGH) }
            }
            return ExtractedField.of(OcrDates.parseToIso(text, today), ExtractionConfidence.LOW)
        }

        /** "dep 06:35" / "STD 07:20" HIGH; a bare "at 21:40" is taken as departure, MEDIUM. */
        private fun extractDepTime(
            text: String,
            patterns: AirlineSmsPatterns,
        ): ExtractedField =
            firstTime(text, patterns.depTime, ExtractionConfidence.HIGH).takeIf { it.isPresent }
                ?: firstTime(text, patterns.anyTime, ExtractionConfidence.MEDIUM)

        private fun firstTime(
            text: String,
            patterns: List<String>,
            confidence: ExtractionConfidence,
        ): ExtractedField {
            for (m in allMatches(text, patterns)) {
                normalizeTime(m.groupValues[1], m.groupValues.getOrNull(2).orEmpty())?.let {
                    return ExtractedField.of(it, confidence)
                }
            }
            return ExtractedField.EMPTY
        }

        /**
         * First terminal mention is the departure terminal unless the 30 characters
         * before it name the arrival airport or say "arriv…" — then it is the arrival's.
         */
        private fun extractTerminals(
            text: String,
            patterns: AirlineSmsPatterns,
            route: Route,
        ): Pair<ExtractedField, ExtractedField> {
            var dep = ExtractedField.EMPTY
            var arr = ExtractedField.EMPTY
            for (m in allMatches(text, patterns.terminal)) {
                val before = text.substring((m.range.first - TERMINAL_CONTEXT).coerceAtLeast(0), m.range.first)
                val toCode = route.to.value
                val isArrival =
                    before.contains("arriv", ignoreCase = true) ||
                        (toCode != null && Regex("""\b${Regex.escape(toCode)}\b""").containsMatchIn(before))
                val field = ExtractedField.of(m.groupValues[1], ExtractionConfidence.MEDIUM)
                if (isArrival) {
                    if (!arr.isPresent) arr = field
                } else if (!dep.isPresent) {
                    dep = field
                }
            }
            return dep to arr
        }

        private fun extractPassenger(
            text: String,
            rules: AirlineSmsRules,
            patterns: AirlineSmsPatterns,
        ): ExtractedField {
            val stop = rules.passengerStopwords.map { it.lowercase() }.toSet()
            patterns.passenger.forEachIndexed { index, pattern ->
                for (m in regex(pattern).findAll(text)) {
                    val name = stripTitles(m.groupValues[1])
                    if (name.isBlank() || name.split(' ').first().lowercase() in stop) continue
                    // Labeled "Passenger: …" first in the data file; greetings are weaker.
                    return ExtractedField.of(name, if (index == 0) ExtractionConfidence.HIGH else ExtractionConfidence.MEDIUM)
                }
            }
            return ExtractedField.EMPTY
        }

        private fun firstGroup(
            text: String,
            patterns: List<String>,
            confidence: ExtractionConfidence,
        ): ExtractedField =
            allMatches(text, patterns).firstOrNull()?.let { ExtractedField.of(it.groupValues[1], confidence) }
                ?: ExtractedField.EMPTY

        private fun allMatches(
            text: String,
            patterns: List<String>,
        ): Sequence<MatchResult> = patterns.asSequence().flatMap { regex(it).findAll(text) }

        /** Rules are strings; compile each once (the file is small, texts are SMS-sized). */
        private val compiled = HashMap<String, Regex>()

        private fun regex(pattern: String): Regex = synchronized(compiled) { compiled.getOrPut(pattern) { Regex(pattern) } }

        private fun stripTitles(raw: String): String =
            raw
                .trim()
                .split(' ')
                .filter { it.isNotEmpty() && it.trimEnd('.').uppercase() !in TITLES }
                .joinToString(" ")

        /** `6:35`, `06.35`, `9:10 pm` → `HH:mm`; null when not a clock time. */
        private fun normalizeTime(
            raw: String,
            meridiem: String,
        ): String? {
            val parts = raw.replace('.', ':').split(':')
            var hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
            when (meridiem.lowercase()) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            return "%02d:%02d".format(hour, minute)
        }

        private fun normalize(raw: String): String = raw.replace('\u00A0', ' ').replace(Regex("""[\r\n\t]+"""), " ").trim()

        private companion object {
            const val TERMINAL_CONTEXT = 30
            val TITLES = setOf("MR", "MRS", "MS", "MSTR", "MISS", "DR")

            /** `QP1421` / `6E2001` — a flight number glued together, not a PNR. */
            val FLIGHT_SHAPE = Regex("""(?:[A-Z][A-Z0-9]|[0-9][A-Z])\d{3,4}""")
        }
    }
