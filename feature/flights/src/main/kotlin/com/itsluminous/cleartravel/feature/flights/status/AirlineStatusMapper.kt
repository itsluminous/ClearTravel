package com.itsluminous.cleartravel.feature.flights.status

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * PURE mapper from a scrape's [ScrapedData] to the ADR-005 [FlightStatusResult].
 *
 * Canonical field vocabulary every FLIGHT rule file emits (single-value `extract`
 * fields and/or one map per result card via `rows`): `flightLabel`, `status`,
 * `aircraftType`, `depAirport`/`arrAirport` (free text, IATA code in parentheses),
 * `depDate`/`arrDate`, `depTimeSched`/`depTimeEst`/`arrTimeSched`/`arrTimeEst`,
 * `depTerminalGate`/`arrTerminalGate` (pipe-separated "Terminal 3 | Gate 24"),
 * `baggageBelt`. Absent keys simply stay unreported (merge-only-known, ADR-005).
 *
 * Multi-card results (Air India returns several `.flight-status-card`s for one
 * query — recon finding) are disambiguated against the saved journey: prefer the
 * card whose departure airport matches, then the card whose departure date matches,
 * else the first card.
 *
 * Times are airport-LOCAL on the source pages; without a timezone database the
 * mapper interprets them in [zone] (defaults to the device zone) — documented
 * best-effort (ADR-010).
 */
object AirlineStatusMapper {
    fun map(
        data: ScrapedData,
        flight: FlightJourney,
        fetchedAt: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FlightStatusResult? {
        val candidates = data.rows.ifEmpty { listOf(data.fields) }
        val card = pickCard(candidates, flight) ?: return null

        val status = mapStatus(card["status"])
        val depDate = parseDate(card["depDate"]) ?: flight.date
        val arrDate = parseDate(card["arrDate"]) ?: depDate
        val (depTerminal, depGate) = splitTerminalGate(card["depTerminalGate"])
        val (arrTerminal, arrGate) = splitTerminalGate(card["arrTerminalGate"])

        val schedDep = toInstant(depDate, card["depTimeSched"] ?: card["depTimeEst"], zone)
        val estDep = toInstant(depDate, card["depTimeEst"], zone)
        val schedArr = toInstant(arrDate, card["arrTimeSched"] ?: card["arrTimeEst"], zone)
        val estArr = toInstant(arrDate, card["arrTimeEst"], zone)

        val anythingKnown =
            status != FlightStatus.UNKNOWN ||
                schedDep != null ||
                depTerminal.isNotEmpty() ||
                depGate.isNotEmpty() ||
                card["aircraftType"].orEmpty().isNotBlank()
        if (!anythingKnown) return null

        return FlightStatusResult(
            status = status,
            schedDep = schedDep,
            schedArr = schedArr,
            estDep = estDep,
            estArr = estArr,
            depTerminal = depTerminal,
            depGate = depGate,
            arrTerminal = arrTerminal,
            arrGate = arrGate,
            baggageBelt = card["baggageBelt"].orEmpty().trim(),
            aircraftType = card["aircraftType"].orEmpty().trim(),
            fetchedAt = fetchedAt,
        )
    }

    /** Prefer the card matching the journey's departure airport, then its date. */
    internal fun pickCard(
        candidates: List<Map<String, String>>,
        flight: FlightJourney,
    ): Map<String, String>? {
        if (candidates.isEmpty()) return null
        val byAirport =
            flight.depAirport.takeIf { it.isNotBlank() }?.let { code ->
                candidates.firstOrNull { extractIata(it["depAirport"]) == code.uppercase() }
            }
        if (byAirport != null) return byAirport
        val byDate =
            flight.date?.let { date ->
                candidates.firstOrNull { parseDate(it["depDate"]) == date }
            }
        return byDate ?: candidates.first()
    }

    /** "Indira Gandhi International Airport (DEL)" → "DEL". */
    internal fun extractIata(airportText: String?): String? = airportText?.let { IATA_IN_PARENS.find(it)?.groupValues?.get(1) }

    /** "Terminal 3 | Gate 24" → ("3", "24"); "Gate N/A"/absent parts → empty. */
    internal fun splitTerminalGate(text: String?): Pair<String, String> {
        if (text.isNullOrBlank()) return "" to ""
        var terminal = ""
        var gate = ""
        for (part in text.split('|')) {
            val trimmed = part.trim()
            TERMINAL_VALUE.find(trimmed)?.let { terminal = it.groupValues[1].trim() }
            GATE_VALUE.find(trimmed)?.let { gate = it.groupValues[1].trim() }
        }
        if (gate.equals("N/A", ignoreCase = true)) gate = ""
        if (terminal.equals("N/A", ignoreCase = true)) terminal = ""
        return terminal to gate
    }

    internal fun mapStatus(text: String?): FlightStatus {
        val normalized = text.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            normalized.isEmpty() -> FlightStatus.UNKNOWN
            "cancel" in normalized -> FlightStatus.CANCELLED
            "delay" in normalized || "late" in normalized -> FlightStatus.DELAYED
            "land" in normalized || "arriv" in normalized -> FlightStatus.LANDED
            "depart" in normalized ||
                "in air" in normalized ||
                "airborne" in normalized ||
                "en route" in normalized -> FlightStatus.DEPARTED
            "board" in normalized || "gate" in normalized -> FlightStatus.BOARDING
            "on time" in normalized ||
                "scheduled" in normalized ||
                "on-time" in normalized -> FlightStatus.SCHEDULED
            else -> FlightStatus.UNKNOWN
        }
    }

    internal fun parseDate(text: String?): LocalDate? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        for (formatter in DATE_FORMATS) {
            runCatching { return LocalDate.parse(value, formatter) }
        }
        return null
    }

    private fun toInstant(
        date: LocalDate?,
        timeText: String?,
        zone: ZoneId,
    ): Instant? {
        if (date == null) return null
        val value = timeText?.trim().orEmpty()
        if (value.isEmpty()) return null
        for (formatter in TIME_FORMATS) {
            runCatching {
                return LocalTime
                    .parse(value, formatter)
                    .atDate(date)
                    .atZone(zone)
                    .toInstant()
            }
        }
        return null
    }

    private val IATA_IN_PARENS = Regex("\\(([A-Z]{3})\\)")
    private val TERMINAL_VALUE = Regex("(?i)terminal\\s*[:#]?\\s*(\\S+)")
    private val GATE_VALUE = Regex("(?i)gate\\s*[:#]?\\s*(\\S+)")
    private val DATE_FORMATS =
        listOf(
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ISO_LOCAL_DATE,
        )
    private val TIME_FORMATS =
        listOf(
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm"),
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH),
        )
}
