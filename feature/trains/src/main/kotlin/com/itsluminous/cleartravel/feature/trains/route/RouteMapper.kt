package com.itsluminous.cleartravel.feature.trains.route

import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.scrape.ScrapedData

/**
 * PURE mapper from the `erail-route` rule's [ScrapedData] shape to the frozen
 * [TrainRouteStop] model (ADR-018). The shape contract (see the rule's fixture
 * `core/scrape/src/test/resources/fixtures/erail-route/expected.json`):
 *
 * - `fields`: `trainNumber`, `trainName` (from the `#divRouteList` header).
 * - `rows`: one map per station with `stationCode`, `stationName`, `arrival`,
 *   `departure`, `halt`, `platform`, `distance`, `day`.
 *
 * Normalization quirks encoded here (recon 2026-09-21, `docs/recon/train-route-NOTES.md`):
 * erail times are dot-separated `HH.MM` (24h); the origin's arrival cell and the
 * terminus' departure cell hold the literals `First` / `Last` instead of a time —
 * both map to the model's "empty = no time at this end" convention. `day` is the
 * cumulative 1-based running day. `halt` and `distance` are extracted by the rule
 * but NOT persisted — [TrainRouteStop] is contract-frozen (ADR-004) and carries no
 * such columns; extending it would need its own ADR + schema migration.
 *
 * Rows whose station name AND code are both blank are dropped. Returns null when
 * fewer than two usable stops remain (not a plausible route) — callers treat that
 * exactly like a parse failure and leave stored data unchanged.
 */
object RouteMapper {
    private const val ROW_STATION_NAME = "stationName"
    private const val ROW_STATION_CODE = "stationCode"
    private const val ROW_ARRIVAL = "arrival"
    private const val ROW_DEPARTURE = "departure"
    private const val ROW_PLATFORM = "platform"
    private const val ROW_DAY = "day"

    private const val MIN_STOPS = 2
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59

    /** Matches `H.MM` / `HH.MM` / `H:MM` / `HH:MM`; anything else is "no time". */
    private val TIME_PATTERN = Regex("^([0-9]{1,2})[.:]([0-9]{2})$")

    fun map(
        ticketId: String,
        data: ScrapedData,
    ): List<TrainRouteStop>? {
        val stops =
            data.rows.mapNotNull { row ->
                val name =
                    row[ROW_STATION_NAME].orEmpty().trim().ifEmpty {
                        row[ROW_STATION_CODE].orEmpty().trim()
                    }
                if (name.isEmpty()) return@mapNotNull null
                RowValues(
                    stationName = name,
                    arrival = normalizeTime(row[ROW_ARRIVAL].orEmpty()),
                    departure = normalizeTime(row[ROW_DEPARTURE].orEmpty()),
                    platform = row[ROW_PLATFORM].orEmpty().trim(),
                    day = row[ROW_DAY].orEmpty().trim().toIntOrNull() ?: 1,
                )
            }
        if (stops.size < MIN_STOPS) return null
        return stops.mapIndexed { index, row ->
            TrainRouteStop(
                ticketId = ticketId,
                stationName = row.stationName,
                arrival = row.arrival,
                departure = row.departure,
                platform = row.platform,
                day = row.day,
                sortOrder = index,
            )
        }
    }

    /**
     * `15.20` → `15:20`, `9.05` → `09:05`; the `First`/`Last` literals — and any
     * other non-time text — become "" (the model's "no time at this end" value).
     */
    private fun normalizeTime(raw: String): String {
        val match = TIME_PATTERN.find(raw.trim()) ?: return ""
        val hour = match.groupValues[1].toIntOrNull() ?: return ""
        val minute = match.groupValues[2].toIntOrNull() ?: return ""
        if (hour > MAX_HOUR || minute > MAX_MINUTE) return ""
        return "%02d:%02d".format(hour, minute)
    }

    private data class RowValues(
        val stationName: String,
        val arrival: String,
        val departure: String,
        val platform: String,
        val day: Int,
    )
}
