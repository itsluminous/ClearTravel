package com.itsluminous.cleartravel.feature.flights.status

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.abs

/**
 * PURE parser for Google's "Flight status" rich card (ADR-026) — the airline-agnostic
 * fallback when no airline rule exists (or an airline rule failed). Fed the same
 * `outerHTML` dump the `google-flights` rule ran on (`ScrapeEvent.Extracted.rawHtml`)
 * and returns an ADR-005 [FlightStatusResult], or null when nothing trustworthy can be
 * read — the caller then keeps the raw page on screen exactly as before.
 *
 * Anchors follow the 2026-09-22 recon (`docs/recon/google-flights-NOTES.md`): the
 * visible `<h2>Flight status</h2>` heading, ARIA roles (`tab`/`tabpanel`/`button`
 * with `aria-expanded`), the `Airport info for XXX` aria-labels, the closed English
 * caption vocabulary (`Scheduled departure`, `Departed`, `Estimated arrival`,
 * `Terminal`, `Gate`, `City · Day, DD Mon`) and the semantic `<del>` holding the
 * original time when a flight deviates from schedule. Google's obfuscated CSS classes
 * are never touched.
 *
 * Safety rule: the card must be for the journey's DATE (the selected date tab, or the
 * departure column's own date caption); anything else returns null rather than writing
 * another day's gate/times onto the saved flight. Times are airport-local without a
 * timezone; like [AirlineStatusMapper] they are interpreted in [zone] (best effort).
 */
object GoogleFlightsExtractor {
    /** Id of the rule file this parser pairs with. */
    const val RULE_ID = "google-flights"

    /** One departure or arrival column of a card. */
    internal data class Side(
        /** Caption above the time, e.g. `Scheduled departure`, `Departed`, `Estimated arrival`. */
        val label: String,
        val time: String,
        /** Struck-through original time; blank when the flight is on schedule. */
        val original: String,
        val terminal: String,
        val gate: String,
        /** `City · Tue, 22 Sept` caption. */
        val cityDate: String,
    )

    /** One collapsible flight card inside the selected date's panel. */
    internal data class Card(
        val headerText: String,
        /** Trailing status text of the header row (`Scheduled`, `On time`, `Delayed …`). */
        val headerStatus: String,
        val originCode: String,
        val destCode: String,
        val departure: Side?,
        val arrival: Side?,
    )

    internal data class Panel(
        val flightLabel: String,
        val selectedDate: String,
        val cards: List<Card>,
    )

    fun map(
        html: String,
        flight: FlightJourney,
        fetchedAt: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FlightStatusResult? = runCatching { mapOrNull(html, flight, fetchedAt, zone) }.getOrNull()

    private fun mapOrNull(
        html: String,
        flight: FlightJourney,
        fetchedAt: Instant,
        zone: ZoneId,
    ): FlightStatusResult? {
        val flightDate = flight.date ?: return null
        val panel = parse(html) ?: return null
        val card = pickCard(panel.cards, flight) ?: return null

        // Date guard: the card must describe the saved journey's day.
        val depDate =
            resolveDate(card.departure?.cityDate, flightDate)
                ?: resolveDate(panel.selectedDate, flightDate)
                ?: return null
        if (depDate != flightDate) return null
        val arrDate = resolveDate(card.arrival?.cityDate, flightDate) ?: depDate

        val dep = card.departure
        val arr = card.arrival
        val depIsActual = dep != null && !dep.label.startsWith("Scheduled", ignoreCase = true)
        val arrIsActual = arr != null && !arr.label.startsWith("Scheduled", ignoreCase = true)

        val depTime = parseTime(dep?.time)
        val depOriginal = parseTime(dep?.original)
        val arrTime = parseTime(arr?.time)
        val arrOriginal = parseTime(arr?.original)

        val schedDepTime = if (depIsActual) depOriginal ?: depTime else depTime
        val estDepTime = if (depIsActual) depTime else null
        val schedArrTime = if (arrIsActual) arrOriginal ?: arrTime else arrTime
        val estArrTime = if (arrIsActual) arrTime else null

        val status = deriveStatus(card, depTime, depOriginal)
        val depTerminal = placeholderToEmpty(dep?.terminal)
        val depGate = placeholderToEmpty(dep?.gate)
        val anythingKnown = status != FlightStatus.UNKNOWN || schedDepTime != null || depTerminal.isNotEmpty() || depGate.isNotEmpty()
        if (!anythingKnown) return null

        return FlightStatusResult(
            status = status,
            schedDep = schedDepTime?.let { it.atDate(depDate).atZone(zone).toInstant() },
            schedArr = schedArrTime?.let { it.atDate(arrDate).atZone(zone).toInstant() },
            estDep = estDepTime?.let { it.atDate(depDate).atZone(zone).toInstant() },
            estArr = estArrTime?.let { it.atDate(arrDate).atZone(zone).toInstant() },
            depTerminal = depTerminal,
            depGate = depGate,
            arrTerminal = placeholderToEmpty(arr?.terminal),
            arrGate = placeholderToEmpty(arr?.gate),
            fetchedAt = fetchedAt,
        )
    }

    /** Structured view of the rich card, or null when the page shows none. */
    internal fun parse(html: String): Panel? {
        val document = Jsoup.parse(html)
        val heading = document.selectFirst(HEADING_SELECTOR) ?: return null
        val root = panelRoot(heading)

        val flightLabel =
            root
                .selectFirst("h3:has(> span:matchesOwn($FLIGHT_CODE_PATTERN))")
                ?.text()
                .orEmpty()
                .clean()
        val selectedTab = root.selectFirst("[role=tab][aria-selected=true]")
        val selectedDate = selectedTab?.text().orEmpty().clean()
        val selectedPanel = selectedTab?.let { tabPanelFor(root, it) }

        // Recon capture: the card sits INSIDE the selected tab's panel. Live DOM
        // (device 2026-09-22): every tabpanel is empty and the current day's card is
        // rendered in an async container next to them — so fall back to the panel
        // root while skipping anything inside a NON-selected (hidden) tabpanel.
        val scope = selectedPanel?.takeIf { it.selectFirst("[role=button][aria-expanded]") != null } ?: root
        val cards =
            scope.select("[role=button][aria-expanded]").mapNotNull { header ->
                if (scope === root && insideOtherTabPanel(header, selectedPanel)) return@mapNotNull null
                val details = detailsFor(scope, header) ?: return@mapNotNull null
                parseCard(header, details)
            }
        return Panel(flightLabel = flightLabel, selectedDate = selectedDate, cards = cards)
    }

    private fun insideOtherTabPanel(
        element: Element,
        selectedPanel: Element?,
    ): Boolean {
        val panel = element.parents().firstOrNull { it.attr("role") == "tabpanel" && it.selectFirst("[role=tab]") == null }
        return panel != null && panel !== selectedPanel
    }

    /** Smallest ancestor of the heading that also holds the tab strip or a card header. */
    private fun panelRoot(heading: Element): Element {
        var current: Element? = heading.parent()
        while (current != null) {
            if (current.selectFirst("[role=tab], [role=button][aria-expanded]") != null) return current
            current = current.parent()
        }
        return heading.ownerDocument() ?: heading
    }

    private fun tabPanelFor(
        root: Element,
        tab: Element,
    ): Element? {
        val controls = tab.attr("aria-controls").trim()
        if (controls.isNotEmpty()) root.getElementById(controls)?.let { return it }
        val id = tab.id().trim()
        if (id.isNotEmpty()) root.selectFirst("[role=tabpanel][aria-labelledby=$id]")?.let { return it }
        return null
    }

    /** The expanded region a card header controls: by `aria-controls`, else its next sibling. */
    private fun detailsFor(
        scope: Element,
        header: Element,
    ): Element? {
        val controls = header.attr("aria-controls").trim()
        if (controls.isNotEmpty()) {
            (scope.getElementById(controls) ?: header.ownerDocument()?.getElementById(controls))?.let { return it }
        }
        return header.nextElementSibling()
    }

    private fun parseCard(
        header: Element,
        details: Element,
    ): Card {
        val headerText = header.text().clean()
        val headerStatus =
            header.selectFirst("*:matchesOwn(^to\\s) + *")?.text()?.clean()
                ?: HEADER_TAIL
                    .find(headerText)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
                    .trim()
        val airportCodes =
            details.select("a[aria-label~=(?i)Airport info for\\s+[A-Z]{3}]").mapNotNull { link ->
                AIRPORT_INFO.find(link.attr("aria-label"))?.groupValues?.get(1)
            }
        var departure: Side? = null
        var arrival: Side? = null
        for (label in details.select("*:matchesOwn($SIDE_LABEL_PATTERN)")) {
            val side = parseSide(label)
            if (DEPARTURE_LABEL.containsMatchIn(side.label)) {
                if (departure == null) departure = side
            } else if (arrival == null) {
                arrival = side
            }
        }
        return Card(
            headerText = headerText,
            headerStatus = headerStatus,
            originCode = airportCodes.getOrNull(0).orEmpty(),
            destCode = airportCodes.getOrNull(1).orEmpty(),
            departure = departure,
            arrival = arrival,
        )
    }

    /** Label element → its column: value is the next sibling, Terminal/Gate are sibling rows. */
    private fun parseSide(label: Element): Side {
        val timeCell = label.parent() ?: label
        val time =
            label
                .nextElementSibling()
                ?.text()
                .orEmpty()
                .clean()
        val original =
            timeCell
                .selectFirst("del")
                ?.text()
                .orEmpty()
                .clean()
        val block = timeCell.parent() ?: timeCell
        val terminal =
            block
                .selectFirst("*:matchesOwn(^\\s*Terminal\\s*$)")
                ?.nextElementSibling()
                ?.text()
                .orEmpty()
                .clean()
        val gate =
            block
                .selectFirst("*:matchesOwn(^\\s*Gate\\s*$)")
                ?.nextElementSibling()
                ?.text()
                .orEmpty()
                .clean()
        val column = block.parent() ?: block
        val cityDate =
            column
                .selectFirst("*:matchesOwn($CITY_DATE_PATTERN)")
                ?.text()
                .orEmpty()
                .clean()
        return Side(
            label = label.ownText().clean(),
            time = time,
            original = original,
            terminal = terminal,
            gate = gate,
            cityDate = cityDate,
        )
    }

    /** Prefer the card leaving the journey's departure airport, then its arrival airport. */
    internal fun pickCard(
        cards: List<Card>,
        flight: FlightJourney,
    ): Card? {
        if (cards.isEmpty()) return null
        val dep = flight.depAirport.trim().uppercase(Locale.ROOT)
        val arr = flight.arrAirport.trim().uppercase(Locale.ROOT)
        return cards.firstOrNull { dep.isNotEmpty() && it.originCode == dep }
            ?: cards.firstOrNull { arr.isNotEmpty() && it.destCode == arr }
            ?: cards.first()
    }

    /**
     * Header/caption vocabulary → [FlightStatus]. "On time" / "Departing on time" /
     * "Scheduled" are all SCHEDULED unless the captions say the flight has already
     * moved; a later-than-original departure time means DELAYED (a struck-through
     * EARLIER time — Google shows early running the same way — does not).
     */
    internal fun deriveStatus(
        card: Card,
        depTime: LocalTime?,
        depOriginal: LocalTime?,
    ): FlightStatus {
        val header = card.headerStatus.lowercase(Locale.ROOT)
        val depLabel =
            card.departure
                ?.label
                .orEmpty()
                .lowercase(Locale.ROOT)
        val arrLabel =
            card.arrival
                ?.label
                .orEmpty()
                .lowercase(Locale.ROOT)
        return when {
            "cancel" in header -> FlightStatus.CANCELLED
            "delay" in header -> FlightStatus.DELAYED
            "landed" in arrLabel || "arrived" in arrLabel || "landed" in header || "arrived" in header -> FlightStatus.LANDED
            "departed" in depLabel || "in air" in header || "departed" in header || "en route" in header -> FlightStatus.DEPARTED
            isLater(depTime, depOriginal) -> FlightStatus.DELAYED
            "board" in header || "gate" in header -> FlightStatus.BOARDING
            header.isNotEmpty() || depLabel.isNotEmpty() -> FlightStatus.SCHEDULED
            else -> FlightStatus.UNKNOWN
        }
    }

    private fun isLater(
        actual: LocalTime?,
        original: LocalTime?,
    ): Boolean {
        if (actual == null || original == null) return false
        val delta = Duration.between(original, actual)
        // A negative delta larger than half a day is a midnight wrap (e.g. 23:50 → 00:20).
        return delta > Duration.ZERO || delta < Duration.ofHours(-12)
    }

    /** `Patna · Tue, 22 Sept` / `Tue, 22 Sept` → the LocalDate nearest [reference]. */
    internal fun resolveDate(
        text: String?,
        reference: LocalDate,
    ): LocalDate? {
        val match = DAY_MONTH.find(text.orEmpty().clean()) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = MONTHS[match.groupValues[2].take(3).lowercase(Locale.ROOT)] ?: return null
        val candidates =
            (-1..1).mapNotNull { offset ->
                runCatching { LocalDate.of(reference.year + offset, month, day) }.getOrNull()
            }
        return candidates.minByOrNull { abs(Duration.between(it.atStartOfDay(), reference.atStartOfDay()).toDays()) }
    }

    internal fun parseTime(text: String?): LocalTime? {
        val value = text?.clean().orEmpty()
        if (value.isEmpty()) return null
        for (formatter in TIME_FORMATS) {
            runCatching { return LocalTime.parse(value, formatter) }
        }
        return null
    }

    private fun placeholderToEmpty(value: String?): String {
        val trimmed = value?.clean().orEmpty()
        return if (trimmed == "-" || trimmed == "–" || trimmed.equals("N/A", ignoreCase = true)) "" else trimmed
    }

    /** Collapses whitespace incl. the narrow/no-break spaces Google puts before `am`/`pm`. */
    private fun String.clean(): String = replace('\u202F', ' ').replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private const val HEADING_SELECTOR = "h2:matchesOwn(^\\s*Flight status\\s*$)"
    private const val FLIGHT_CODE_PATTERN = "^[A-Z0-9]{2}\\s?\\d{1,4}$"
    private const val SIDE_LABEL_PATTERN =
        "^\\s*((Scheduled|Estimated|Actual|Expected|Revised) (departure|arrival)|Departed|Departing|Landed|Arrived|Arriving)\\s*$"
    private const val CITY_DATE_PATTERN = "^[^·]+·\\s*[A-Za-z]{3},\\s*\\d{1,2}\\s+[A-Za-z]{3,4}\\.?\\s*$"
    private val DEPARTURE_LABEL = Regex("(?i)departure|departed|departing")
    private val HEADER_TAIL = Regex("\\bto\\s+.+?\\s+[A-Z]{3}\\s+(.*)$")
    private val AIRPORT_INFO = Regex("(?i)Airport info for\\s+([A-Z]{3})")
    private val DAY_MONTH = Regex("(?i)\\b(\\d{1,2})\\s+([A-Za-z]{3,4})\\.?\\b")
    private val MONTHS =
        listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .withIndex()
            .associate { (index, name) -> name to index + 1 }
    private val TIME_FORMATS: List<DateTimeFormatter> =
        listOf(
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mm a").toFormatter(Locale.ENGLISH),
            DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("h:mma").toFormatter(Locale.ENGLISH),
            DateTimeFormatter.ofPattern("HH:mm"),
            DateTimeFormatter.ofPattern("H:mm"),
        )
}
