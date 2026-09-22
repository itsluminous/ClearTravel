package com.itsluminous.cleartravel.feature.flights.status

import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Fallbacks and per-rule quirks of the flight status scrape flow.
 *
 * Unknown airline / no rule file (e.g. SpiceJet — structurally unscrapeable, recon
 * finding) → the spec fallback: open a plain web search for "<airline> <flight>
 * status" in the WebView and let the user read it; manual edit stays available.
 */
object FlightStatusFallbacks {
    /** Search URL for the no-rule fallback. [airlineName] improves the query when known. */
    fun webSearchUrl(
        airlineIata: String,
        flightNumber: String,
        airlineName: String? = null,
    ): String {
        val airline = airlineName?.takeIf { it.isNotBlank() } ?: airlineIata
        val query = "$airline $airlineIata $flightNumber flight status"
        return "https://www.google.com/search?q=" + URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    /** Fallback when the data file has no check-in URL for the airline (spec). */
    fun checkInSearchUrl(
        airlineIata: String,
        airlineName: String? = null,
    ): String {
        val airline = airlineName?.takeIf { it.isNotBlank() } ?: airlineIata
        val query = "$airline web check-in"
        return "https://www.google.com/search?q=" + URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    /**
     * The `{date}` placeholder shape each rule's URL expects. The frozen ScrapeRule
     * schema has no date-format field (core:scrape is not ours to change), so the
     * per-rule format lives here, keyed by rule id — default ISO (ADR-013).
     */
    fun formatDateForRule(
        ruleId: String,
        date: LocalDate,
    ): String = date.format(DATE_FORMATS[ruleId] ?: DateTimeFormatter.ISO_LOCAL_DATE)

    private val DATE_FORMATS: Map<String, DateTimeFormatter> =
        mapOf(
            // airindia.com result URLs use ?fno=101&on=20260920 (recon capture).
            "airindia" to DateTimeFormatter.ofPattern("uuuuMMdd"),
            // Google search query words: `SG+128+flight+status+22+September+2026`. A bare
            // query pre-selects the NEXT operating day once today's departure time has
            // passed (SG 128 on 2026-09-22 was cancelled — Google showed Wed 23 Sept);
            // spelling the date out makes Google select the journey's tab (ADR-026 v2).
            GoogleFlightsExtractor.RULE_ID to DateTimeFormatter.ofPattern("d'+'MMMM'+'uuuu", Locale.ENGLISH),
        )
}
