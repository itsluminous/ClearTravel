package com.itsluminous.cleartravel.feature.trains.route

import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.scrape.ScrapedData

/**
 * PURE mapper from a train-route rule's [ScrapedData] shape to the frozen
 * [TrainRouteStop] model (ADR-018/ADR-019). ONE mapper serves both route sources —
 * their row keys overlap by design:
 *
 * - `ixigo-route` (PRIMARY): rows with `stationCode`, `stationName`, `arrival`,
 *   `departure`, `halt`, `platform`, `distance`, `day`. Times are colon `HH:MM`;
 *   the origin/terminus carry the literals `starts` / `ends`; platform can be the
 *   placeholder `-`.
 * - `erail-route` (fallback, live MOBILE layout): rows with `stationName`,
 *   `arrival`, `departure`, `distance`, `platform` — NO day column. Times are
 *   dot `HH.MM`; end literals are `First` / `Last`.
 *
 * Normalization: any `H.MM`/`HH.MM`/`H:MM`/`HH:MM` becomes zero-padded `HH:mm`;
 * every non-time literal (`starts`, `ends`, `First`, `Last`, garbage) maps to the
 * model's "empty = no time at this end" convention. A `-` platform placeholder
 * becomes empty ("platform when known"). `day` is read as the cumulative 1-based
 * running day when the source provides it; when the source has NO day column it is
 * INFERRED: the day increments whenever a stop's reference time (arrival, falling
 * back to departure) moves backwards past midnight relative to the previous stop —
 * keeping the erail fallback multi-day-correct (ADR-019). `halt` and `distance`
 * are extracted by the rules but NOT persisted — [TrainRouteStop] is
 * contract-frozen (ADR-004); the route page derives halt from the arr/dep delta
 * instead (ADR-019).
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
    private const val MINUTES_PER_HOUR = 60
    private const val PLATFORM_PLACEHOLDER = "-"

    /** Matches `H.MM` / `HH.MM` / `H:MM` / `HH:MM`; anything else is "no time". */
    private val TIME_PATTERN = Regex("^([0-9]{1,2})[.:]([0-9]{2})$")

    fun map(
        ticketId: String,
        data: ScrapedData,
    ): List<TrainRouteStop>? {
        val stops =
            data.rows.mapNotNull { row ->
                val rawName = row[ROW_STATION_NAME].orEmpty().trim()
                val code = row[ROW_STATION_CODE].orEmpty().trim()
                // "Udhna Junction (UDN)" — the code suffix makes stops matchable
                // against the ticket's boarding-station code from the PNR result.
                val name =
                    when {
                        rawName.isEmpty() -> code.uppercase()
                        code.isNotEmpty() && !rawName.contains("($code)", ignoreCase = true) ->
                            "$rawName (${code.uppercase()})"
                        else -> rawName
                    }
                if (name.isEmpty()) return@mapNotNull null
                RowValues(
                    stationName = name,
                    arrival = normalizeTime(row[ROW_ARRIVAL].orEmpty()),
                    departure = normalizeTime(row[ROW_DEPARTURE].orEmpty()),
                    platform = normalizePlatform(row[ROW_PLATFORM].orEmpty()),
                    day = row[ROW_DAY]?.trim()?.toIntOrNull(),
                )
            }
        if (stops.size < MIN_STOPS) return null
        val days = resolveDays(stops)
        return stops.mapIndexed { index, row ->
            TrainRouteStop(
                ticketId = ticketId,
                stationName = row.stationName,
                arrival = row.arrival,
                departure = row.departure,
                platform = row.platform,
                day = days[index],
                sortOrder = index,
            )
        }
    }

    /**
     * Day per stop, hybrid per row: the source's own day column when the cell
     * parses (ixigo), otherwise inferred by midnight crossings — the running day
     * increments when a stop's reference time is earlier than the previous stop's
     * (a train never travels back in time within a day). This keeps the day-less
     * erail mobile fallback multi-day-correct and lets a single malformed day cell
     * carry the running day forward instead of resetting it.
     */
    private fun resolveDays(stops: List<RowValues>): List<Int> {
        var day = 1
        var previousMinutes: Int? = null
        return stops.map { stop ->
            val minutes = referenceMinutes(stop)
            if (stop.day != null) {
                day = stop.day
            } else if (minutes != null && previousMinutes != null && minutes < previousMinutes!!) {
                day += 1
            }
            if (minutes != null) previousMinutes = minutes
            day
        }
    }

    /** Minutes-of-day of the stop's arrival, falling back to its departure. */
    private fun referenceMinutes(stop: RowValues): Int? {
        val time = stop.arrival.ifEmpty { stop.departure }
        val match = TIME_PATTERN.find(time) ?: return null
        return match.groupValues[1].toInt() * MINUTES_PER_HOUR + match.groupValues[2].toInt()
    }

    /**
     * `15.20` → `15:20`, `9.05` → `09:05`; the `starts`/`ends` (ixigo) and
     * `First`/`Last` (erail) literals — and any other non-time text — become ""
     * (the model's "no time at this end" value).
     */
    private fun normalizeTime(raw: String): String {
        val match = TIME_PATTERN.find(raw.trim()) ?: return ""
        val hour = match.groupValues[1].toIntOrNull() ?: return ""
        val minute = match.groupValues[2].toIntOrNull() ?: return ""
        if (hour > MAX_HOUR || minute > MAX_MINUTE) return ""
        return "%02d:%02d".format(hour, minute)
    }

    /** ixigo uses a literal `-` where the platform is unknown — store "" instead. */
    private fun normalizePlatform(raw: String): String = raw.trim().takeIf { it != PLATFORM_PLACEHOLDER }.orEmpty()

    private data class RowValues(
        val stationName: String,
        val arrival: String,
        val departure: String,
        val platform: String,
        val day: Int?,
    )
}
