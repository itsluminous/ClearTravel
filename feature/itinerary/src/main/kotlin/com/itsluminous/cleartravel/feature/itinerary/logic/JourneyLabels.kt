package com.itsluminous.cleartravel.feature.itinerary.logic

import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.TrainTicket

// The human handle of a linked journey (ADR-028 follow-up): the train NUMBER and the
// airline + flight number — the same text the journey picker rows show, so the item
// sheet, the form and the picker never disagree. Pure; the id-prefix fallback is what
// callers render when the journey is no longer in Room (deleted / not restored).

/** `12951` — a ticket's train number, or null when the ticket carries none. */
fun trainJourneyLabel(ticket: TrainTicket): String? = ticket.trainNumber.trim().takeIf(String::isNotBlank)

/** `6E 2001` — airline IATA + flight number; either half alone when the other is blank. */
fun flightJourneyLabel(flight: FlightJourney): String? =
    listOf(flight.airlineIata, flight.flightNumber)
        .map(String::trim)
        .filter(String::isNotBlank)
        .joinToString(" ")
        .takeIf(String::isNotBlank)

/** The stored id shortened for display when the journey itself cannot be resolved. */
fun journeyIdFallback(journeyId: String): String = journeyId.take(JOURNEY_ID_PREFIX_LENGTH)

private const val JOURNEY_ID_PREFIX_LENGTH = 8
