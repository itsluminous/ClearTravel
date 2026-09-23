package com.itsluminous.cleartravel.feature.flights.share

import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.SharePayloadMappers
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import com.itsluminous.cleartravel.core.model.FlightJourney
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * PURE share-caption builder for a flight (ADR-039 part A). The link carries the
 * flight's ADD data as a `share/flight/<blob>` payload, so a recipient with the app
 * lands in a prefilled add form (there is no PNR-status page to point at, unlike
 * trains — ADR-020).
 */
object FlightShareText {
    /** `24 Sep` — the compact date shape used in the caption. */
    private val CAPTION_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** `AI 101 · 24 Sep · BLR → DEL` (segments omitted when unknown). */
    fun headline(
        flight: FlightJourney,
        routeSeparator: String,
    ): String {
        val number = "${flight.airlineIata} ${flight.flightNumber}".trim()
        val date = flight.date?.let(::formatDate)
        val route =
            listOf(flight.depAirport, flight.arrAirport)
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" $routeSeparator ")
        return listOfNotNull(number, date, route).joinToString(" · ")
    }

    /** The https share link for [flight] (never `TooLong` in practice — a flight payload is ~150 chars). */
    fun shareUrl(flight: FlightJourney): ShareUrlResult = ShareLinkCodec.buildShareUrl(SharePayloadMappers.toPayload(flight))

    /**
     * Share-sheet caption. [template] is the localized `flights_share_text` resource
     * (`%1$s` = headline, `%2$s` = link) — passed in so this stays pure (hard rule 1).
     */
    fun buildShareText(
        flight: FlightJourney,
        url: String,
        template: String,
        routeSeparator: String,
    ): String = String.format(template, headline(flight, routeSeparator), url)

    private fun formatDate(date: LocalDate): String = CAPTION_DATE.format(date)
}
