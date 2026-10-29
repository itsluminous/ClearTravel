package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence

/** One `carrier + flight number` mention in a line of upper-cased OCR text. */
internal data class FlightToken(
    /** Airline IATA code, e.g. `IX`. */
    val carrier: String,
    /** Flight number with leading zeros stripped, e.g. `2019`. */
    val number: String,
    val confidence: ExtractionConfidence,
    /** Character range of the mention inside the line (for overlap suppression). */
    val range: IntRange,
) {
    /** ADR-025 identity half: `IX2019`. */
    val key: String get() = carrier + number
}

/**
 * Finds flight-number mentions (ADR-045, shared by the boarding-pass and
 * booking-confirmation extractors). Three forms, in priority order, each suppressing
 * overlapping lower-priority matches:
 *
 * 1. **Labeled** — `FLIGHT 6E 2345`, `Flight No: AI-0865` → HIGH.
 * 2. **Airline alias** — `Air India Express 2019`, `Air India Express IX-2019`,
 *    `IndiGo 6E 6114` → the alias's code from [FlightDocumentRules] (MEDIUM). When the
 *    document also prints a code and it disagrees with the alias (OCR `lX-1542`), the
 *    alias wins — names survive OCR far better than two-letter codes.
 * 3. **Bare** — `6E 6114`, `AI 0503`, `IX-2019` → MEDIUM, filtered against the
 *    stop-word list (`ON 29`, `RS 4520`), unit suffixes (`2h 35m`, `15 Kgs`), aircraft
 *    type names (`A320`, `B737`) and amounts whose currency sign OCR turned into a
 *    letter (`T944`, `Z40650` — a letter+digit carrier never prints tight against
 *    its number).
 */
internal class FlightTokens(
    val rules: FlightDocumentRules = FlightDocumentRules.BUNDLED,
) {
    private val aliasFlight: List<Pair<Regex, String>> by lazy {
        rules.aliasPatterns.map { (aliasRegex, code) ->
            Regex(
                aliasRegex.pattern +
                    """\s*(?:FLIGHT\s*)?(?:NO\.?\s*)?[:\-]?\s*(?:([A-Z][A-Z0-9]|\d[A-Z])\s*-?\s*)?(\d{2,4})\b""" +
                    UNIT_GUARD,
            ) to code
        }
    }

    private val stopwords: Set<String> = rules.flightCodeStopwords.map { it.uppercase() }.toSet()

    /** All tokens in [line] (upper-cased), in text order, overlaps resolved. */
    fun findAll(line: String): List<FlightToken> {
        val found = mutableListOf<FlightToken>()
        LABELED.findAll(line).forEach { m ->
            found += FlightToken(m.groupValues[1], strip(m.groupValues[2]), ExtractionConfidence.HIGH, m.range)
        }
        for ((regex, code) in aliasFlight) {
            regex.findAll(line).forEach { m ->
                if (found.none { it.range.overlaps(m.range) }) {
                    found += FlightToken(code, strip(m.groupValues[2]), ExtractionConfidence.MEDIUM, m.range)
                }
            }
        }
        BARE.findAll(line).forEach { m ->
            val carrier = m.groupValues[1]
            val digits = m.groupValues[2]
            val separated = m.value.length > carrier.length + digits.length
            val aircraft = !separated && AIRCRAFT_PREFIX.matches(carrier) && digits.length == 2
            // `T944`, `Z40650` — a currency sign OCR'd as a letter in front of an amount:
            // a letter+digit carrier never prints tight against its number.
            val amount = !separated && AMOUNT_PREFIX.matches(carrier)
            if (carrier !in stopwords && !aircraft && !amount && found.none { it.range.overlaps(m.range) }) {
                found += FlightToken(carrier, strip(digits), ExtractionConfidence.MEDIUM, m.range)
            }
        }
        return found.filter { it.number.isNotEmpty() }.sortedBy { it.range.first }
    }

    /** The best token in [text] (labeled first, then document order), or null. */
    fun first(text: String): FlightToken? {
        val all = text.lines().flatMap { findAll(it) }
        return all.firstOrNull { it.confidence == ExtractionConfidence.HIGH } ?: all.firstOrNull()
    }

    private fun strip(number: String): String = number.trimStart('0')

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last

    private companion object {
        /**
         * `2h 35m`, `15 Kgs`, `2 hours`, `48%` — never a flight number; nor an amount
         * whose currency sign OCR turned into a letter (`₹14723.00` → `Z14723.00`).
         */
        const val UNIT_GUARD = """(?!\s*(?:M|MIN|MINS|H|HR|HRS|HOURS?|KG|KGS|PIECE|PIECES|PCS|%)\b)(?![.,]\d)"""
        val LABELED =
            Regex("""FLIGHT\s*(?:NO\.?|NUMBER)?\s*[:\-]?\s*([A-Z][A-Z0-9]|\d[A-Z])\s*-?\s*(\d{1,4})\b$UNIT_GUARD""")
        val BARE = Regex("""\b([A-Z][A-Z0-9]|\d[A-Z])[ ]?-?[ ]?(\d{2,4})\b$UNIT_GUARD""")
        val AIRCRAFT_PREFIX = Regex("""[AB]\d""")
        val AMOUNT_PREFIX = Regex("""[A-Z]\d""")
    }
}
