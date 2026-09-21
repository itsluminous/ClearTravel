package com.itsluminous.cleartravel.feature.trains.seatmap

/**
 * PURE resolution of which seat-layout class a coach uses (ADR-022). The coach code
 * printed on the rake decides first — Indian Railways coach prefixes are stable:
 *
 * | Prefix / code | Layout | Notes |
 * |---|---|---|
 * | `S`  | `SL` | Sleeper (`S1`…`S12`) |
 * | `B`, `M` | `3A` | AC 3-tier (`B1`…), LHB 3-tier economy (`M1`…) |
 * | `A`  | `2A` | AC 2-tier |
 * | `H`  | `1A` | AC first (`H1`, `HA1` composite) |
 * | `C`  | `CC` | AC chair car |
 * | `E`  | `EC` | Executive chair car |
 * | `D`  | `2S` | Second sitting |
 * | `GN`, `GS`, `UR`, `SLR` | `GN` | General / unreserved (incl. seating-cum-luggage) |
 * | `EN`, `EOG`, `PC`, `RMS`, `LOCO` | none | Engine, generator, pantry, mail — no seats |
 *
 * When the coach code is blank or unknown the ticket's booked travel class decides
 * (`3E` → `3A`, `FC` → `1A`, `EA`/`EV` → `EC` aliases); a class with no layout file
 * resolves to null and the UI shows its "no layout" state.
 */
object TrainClassResolver {
    private val EXACT =
        mapOf(
            "GN" to "GN",
            "GS" to "GN",
            "UR" to "GN",
            "SLR" to "GN",
        )
    private val NO_SEATS = setOf("EN", "EOG", "PC", "RMS", "LOCO", "SLRD")
    private val PREFIX =
        mapOf(
            'S' to "SL",
            'B' to "3A",
            'M' to "3A",
            'A' to "2A",
            'H' to "1A",
            'C' to "CC",
            'E' to "EC",
            'D' to "2S",
        )
    private val CLASS_ALIASES =
        mapOf(
            "3E" to "3A",
            "FC" to "1A",
            "EA" to "EC",
            "EV" to "EC",
        )
    private val KNOWN_CLASSES = setOf("SL", "3A", "2A", "1A", "CC", "EC", "2S", "GN")

    /** Coach-code pattern: letters then digits (`S1`, `B12`, `HA1`). */
    private val CODE_PATTERN = Regex("^([A-Z]+)([0-9]*)$")

    /** Layout class for [coachCode], null when the code is a non-passenger coach or unknown. */
    fun fromCoachCode(coachCode: String): String? {
        val code = coachCode.trim().uppercase()
        if (code.isEmpty()) return null
        if (code in NO_SEATS) return null
        EXACT[code]?.let { return it }
        val match = CODE_PATTERN.find(code) ?: return null
        val letters = match.groupValues[1]
        if (letters in NO_SEATS) return null
        return PREFIX[letters.first()]
    }

    /** Layout class for a booked travel class (`SL`, `3A`, aliases), null when unknown. */
    fun fromTravelClass(travelClass: String): String? {
        val cls = travelClass.trim().uppercase()
        if (cls.isEmpty()) return null
        CLASS_ALIASES[cls]?.let { return it }
        return cls.takeIf { it in KNOWN_CLASSES }
    }

    /** Coach code first, ticket class as fallback. */
    fun resolve(
        coachCode: String,
        travelClass: String,
    ): String? = fromCoachCode(coachCode) ?: fromTravelClass(travelClass)
}
