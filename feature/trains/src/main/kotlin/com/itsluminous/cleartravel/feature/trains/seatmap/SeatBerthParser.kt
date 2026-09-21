package com.itsluminous.cleartravel.feature.trains.seatmap

/**
 * PURE parser for the free-text `TrainPassenger.seatBerth` field (ADR-022). The
 * field arrives in many shapes — `"32"`, `"32 LB"`, `"LB 32"`, `"B4 32"`, `"B4-32"`,
 * `"S1/12"`, `"12A"` (chair-car seat letter), `"0"` for unallotted — and the seat
 * map needs the bare berth number. Rule: the FIRST run of digits that is NOT
 * immediately preceded by a letter (so the `4` of `B4` is skipped) is the berth;
 * `0` and non-positive values mean "no berth" → null.
 */
object SeatBerthParser {
    private val BERTH = Regex("(?<![A-Za-z0-9])([0-9]{1,3})(?![0-9])")

    fun berthNumber(seatBerth: String): Int? {
        val match = BERTH.find(seatBerth) ?: return null
        return match.groupValues[1].toInt().takeIf { it > 0 }
    }
}
