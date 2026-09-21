package com.itsluminous.cleartravel.feature.trains.route

import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.scrape.ScrapedData

/**
 * PURE mapper from the `ixigo-route` rule's `coaches` extra row-set (ADR-022) to
 * [TrainCoach] rows in rake order. Each row carries a single `code` — the box text
 * of one physical coach (`EN`, `GN`, `S1`, `PC`, `B4` …) — and `sortOrder` is the
 * extraction index, which is the physical position from the engine.
 *
 * Normalization: codes are trimmed and upper-cased (the capture carries trailing
 * spaces — `"EN "`); blank rows are dropped. Duplicate codes (`GN` at both ends of
 * a rake) are KEPT — they are distinct coaches. Returns null when the row-set is
 * absent or resolves to nothing usable — the caller keeps whatever coaches are
 * stored, because a route fetched from a source without coach data (erail) must
 * never wipe a previously fetched composition.
 */
object CoachMapper {
    /** The `ScrapeRule.extraRows` key the ixigo route rule declares. */
    const val ROW_SET = "coaches"
    private const val ROW_CODE = "code"

    fun map(
        ticketId: String,
        data: ScrapedData,
    ): List<TrainCoach>? {
        val rows = data.extraRows[ROW_SET] ?: return null
        val codes = rows.mapNotNull { row -> row[ROW_CODE]?.trim()?.uppercase()?.takeIf(String::isNotEmpty) }
        if (codes.isEmpty()) return null
        return codes.mapIndexed { index, code ->
            TrainCoach(ticketId = ticketId, code = code, sortOrder = index)
        }
    }
}
