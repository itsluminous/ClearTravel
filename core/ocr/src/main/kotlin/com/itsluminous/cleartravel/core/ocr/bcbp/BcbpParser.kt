package com.itsluminous.cleartravel.core.ocr.bcbp

import java.time.LocalDate

/** Result of a BCBP decode attempt: structured data or a diagnostic failure reason. */
sealed interface BcbpParseResult {
    data class Success(
        val data: BcbpData,
    ) : BcbpParseResult

    data class Failure(
        val reason: String,
    ) : BcbpParseResult
}

/** Parsed IATA BCBP (Bar Coded Boarding Pass) content: passenger + one entry per leg. */
data class BcbpData(
    /** Raw BCBP name, `LASTNAME/FIRSTNAME` with padding trimmed. */
    val passengerName: String,
    val legs: List<BcbpLeg>,
) {
    /** `LASTNAME/FIRSTNAME TITLE` reordered to a display-friendly `FIRSTNAME LASTNAME`. */
    val displayName: String
        get() {
            val slash = passengerName.indexOf('/')
            if (slash < 0) return passengerName
            val last = passengerName.substring(0, slash).trim()
            val first = passengerName.substring(slash + 1).trim()
            return listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        }
}

/** One flight leg from the BCBP mandatory repeated block. */
data class BcbpLeg(
    /** Operating carrier PNR / booking reference. */
    val pnr: String,
    val fromAirport: String,
    val toAirport: String,
    val carrier: String,
    /** Flight number with leading zeros stripped (alpha suffix kept). */
    val flightNumber: String,
    /** Raw Julian day-of-year from the barcode (1..366), or null if non-numeric. */
    val julianDate: Int?,
    /** [julianDate] resolved to an ISO-8601 date against a reference day, when possible. */
    val flightDateIso: String?,
    val compartment: String,
    /** Seat with leading zeros stripped, e.g. `072C` -> `72C`. INF = infant, no seat. */
    val seat: String,
    /** Check-in sequence number with leading zeros stripped. */
    val sequenceNumber: String,
    val passengerStatus: String,
)

/**
 * Pure parser for the IATA Resolution 792 BCBP type "M" string (the payload of the
 * PDF417/Aztec/QR barcode on boarding passes). Parses the mandatory 60-character
 * block of leg 1 and is tolerant of additional legs and truncated/absent
 * conditional sections: legs that cannot be fully read are simply omitted.
 */
object BcbpParser {
    private const val MANDATORY_UNIQUE_LENGTH = 23
    private const val REPEATED_BLOCK_LENGTH = 37
    private const val MIN_LENGTH = MANDATORY_UNIQUE_LENGTH + REPEATED_BLOCK_LENGTH

    /**
     * Parses [raw] BCBP content. [today] anchors Julian-date resolution (the barcode
     * carries only a day-of-year); injectable for deterministic tests.
     */
    fun parse(
        raw: String,
        today: LocalDate = LocalDate.now(),
    ): BcbpParseResult {
        val text = raw.trimEnd('\n', '\r')
        if (text.length < MIN_LENGTH) {
            return BcbpParseResult.Failure(
                "Too short for a BCBP mandatory block (${text.length} < $MIN_LENGTH)",
            )
        }
        if (text[0] != 'M') {
            return BcbpParseResult.Failure("Unsupported format code '${text[0]}' (expected 'M')")
        }
        val legCount = text[1].digitToIntOrNull()
        if (legCount == null || legCount < 1) {
            return BcbpParseResult.Failure("Invalid leg count '${text[1]}'")
        }
        val passengerName = text.substring(2, 22).trim()
        if (passengerName.isEmpty()) {
            return BcbpParseResult.Failure("Blank passenger name")
        }

        val legs = mutableListOf<BcbpLeg>()
        var offset = MANDATORY_UNIQUE_LENGTH
        for (i in 0 until legCount) {
            if (offset + REPEATED_BLOCK_LENGTH > text.length) break // tolerate truncation
            val block = text.substring(offset, offset + REPEATED_BLOCK_LENGTH)
            legs += parseLeg(block, today)
            // The last 2 chars of the repeated block declare the size (hex) of the
            // variable/conditional field that follows; skip it to reach the next leg.
            val varSize = block.substring(35, 37).toIntOrNull(16) ?: 0
            offset += REPEATED_BLOCK_LENGTH + varSize
        }
        if (legs.isEmpty()) {
            return BcbpParseResult.Failure("No parseable legs")
        }
        return BcbpParseResult.Success(BcbpData(passengerName = passengerName, legs = legs))
    }

    private fun parseLeg(
        block: String,
        today: LocalDate,
    ): BcbpLeg {
        val julian =
            block
                .substring(21, 24)
                .trim()
                .toIntOrNull()
                ?.takeIf { it in 1..366 }
        return BcbpLeg(
            pnr = block.substring(0, 7).trim(),
            fromAirport = block.substring(7, 10).trim(),
            toAirport = block.substring(10, 13).trim(),
            carrier = block.substring(13, 16).trim(),
            flightNumber = stripLeadingZeros(block.substring(16, 21).trim()),
            julianDate = julian,
            flightDateIso = julian?.let { resolveJulianDate(it, today)?.toString() },
            compartment = block.substring(24, 25).trim(),
            seat = stripLeadingZeros(block.substring(25, 29).trim()),
            sequenceNumber = stripLeadingZeros(block.substring(29, 34).trim()),
            passengerStatus = block.substring(34, 35).trim(),
        )
    }

    /**
     * Resolves a Julian day-of-year to a concrete date: among the candidate dates in
     * last/this/next year, picks the one closest to [today] (boarding passes are
     * scanned near the travel date).
     */
    fun resolveJulianDate(
        dayOfYear: Int,
        today: LocalDate,
    ): LocalDate? =
        (-1..1)
            .mapNotNull { delta ->
                val year = today.year + delta
                val maxDay = if (LocalDate.of(year, 1, 1).isLeapYear) 366 else 365
                if (dayOfYear in 1..maxDay) LocalDate.ofYearDay(year, dayOfYear) else null
            }.minByOrNull { candidate ->
                kotlin.math.abs(candidate.toEpochDay() - today.toEpochDay())
            }

    private fun stripLeadingZeros(value: String): String {
        val stripped = value.trimStart('0')
        return if (stripped.isEmpty() && value.isNotEmpty()) "0" else stripped
    }
}
