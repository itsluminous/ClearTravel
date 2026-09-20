package com.itsluminous.cleartravel.core.google.calendar

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.TrainPassenger
import com.itsluminous.cleartravel.core.model.TrainRouteStop
import com.itsluminous.cleartravel.core.model.TrainTicket
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Row → event field mapping for every synced shape (pure, fixed strings). */
class CalendarEventMapperTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val strings =
        CalendarEventStrings(
            trainSummary = "Train %1\$s: %2\$s → %3\$s",
            flightSummary = "Flight %1\$s: %2\$s → %3\$s",
            commuteSummary = "%1\$s → %2\$s",
            pnrLine = "PNR: %1\$s",
            seatLine = "Seat: %1\$s",
            passengerLine = "%1\$s — %2\$s (%3\$s)",
        )

    @Test
    fun `place with planned time maps to a one-hour timed event with coordinates in location`() {
        val item =
            ItineraryItem(
                tripId = "trip-1",
                name = "Eiffel Tower",
                date = LocalDate.of(2026, 10, 2),
                plannedTime = "09:30",
                latitude = 48.8584,
                longitude = 2.2945,
                note = "Buy tickets online",
            )

        val event = CalendarEventMapper.itineraryEvent(item, strings, zone)!!

        assertThat(event.summary).isEqualTo("Eiffel Tower")
        assertThat(event.location).isEqualTo("Eiffel Tower (48.8584, 2.2945)")
        assertThat(event.description).isEqualTo("Buy tickets online")
        assertThat(event.startDateTime).isEqualTo(LocalDateTime.of(2026, 10, 2, 9, 30))
        assertThat(event.endDateTime).isEqualTo(LocalDateTime.of(2026, 10, 2, 10, 30))
        assertThat(event.timeZone).isEqualTo(zone.id)
        assertThat(event.privateProperties[CalendarEventProps.ROW_ID]).isEqualTo(item.id)
    }

    @Test
    fun `place without planned time maps to an all-day event`() {
        val item =
            ItineraryItem(
                tripId = "trip-1",
                name = "Louvre",
                date = LocalDate.of(2026, 10, 3),
            )

        val event = CalendarEventMapper.itineraryEvent(item, strings, zone)!!

        assertThat(event.isAllDay).isTrue()
        assertThat(event.startDate).isEqualTo(LocalDate.of(2026, 10, 3))
        assertThat(event.endDateExclusive).isEqualTo(LocalDate.of(2026, 10, 4))
    }

    @Test
    fun `undated itinerary item is unsyncable, not an error`() {
        val item = ItineraryItem(tripId = "trip-1", name = "Somewhere", date = null)

        assertThat(CalendarEventMapper.itineraryEvent(item, strings, zone)).isNull()
    }

    @Test
    fun `commute leg maps from → to into summary and origin into location`() {
        val item =
            ItineraryItem(
                tripId = "trip-1",
                type = ItineraryItemType.COMMUTE,
                name = "To the airport",
                date = LocalDate.of(2026, 10, 4),
                plannedTime = "06:00",
                commuteMode = CommuteMode.CAB,
                fromName = "Hotel Le Six",
                toName = "CDG Airport",
            )

        val event = CalendarEventMapper.itineraryEvent(item, strings, zone)!!

        assertThat(event.summary).isEqualTo("Hotel Le Six → CDG Airport")
        assertThat(event.location).isEqualTo("Hotel Le Six")
        assertThat(event.startDateTime).isEqualTo(LocalDateTime.of(2026, 10, 4, 6, 0))
    }

    @Test
    fun `train maps route times, PNR and per-passenger seat summary`() {
        val ticket =
            TrainTicket(
                pnr = "1234567890",
                trainNumber = "12951",
                trainName = "Rajdhani",
                journeyDate = LocalDate.of(2026, 11, 1),
                fromStation = "NDLS",
                toStation = "BCT",
            )
        val passengers =
            listOf(
                TrainPassenger(ticketId = ticket.id, name = "Asha", coach = "B4", seatBerth = "32 LB", bookingStatus = "CNF"),
                TrainPassenger(ticketId = ticket.id, name = "Ravi", coach = "B4", seatBerth = "33 MB", currentStatus = "RAC 4"),
            )
        val stops =
            listOf(
                TrainRouteStop(ticketId = ticket.id, stationName = "NDLS", departure = "16:25", day = 1, sortOrder = 0),
                TrainRouteStop(ticketId = ticket.id, stationName = "BCT", arrival = "08:15", day = 2, sortOrder = 1),
            )

        val event = CalendarEventMapper.trainEvent(ticket, passengers, stops, strings, zone)!!

        assertThat(event.summary).isEqualTo("Train 12951 Rajdhani: NDLS → BCT")
        assertThat(event.location).isEqualTo("NDLS")
        assertThat(event.startDateTime).isEqualTo(LocalDateTime.of(2026, 11, 1, 16, 25))
        assertThat(event.endDateTime).isEqualTo(LocalDateTime.of(2026, 11, 2, 8, 15))
        assertThat(event.description)
            .isEqualTo("PNR: 1234567890\nAsha — B4 32 LB (CNF)\nRavi — B4 33 MB (RAC 4)")
    }

    @Test
    fun `train without route times falls back to an all-day event`() {
        val ticket =
            TrainTicket(
                pnr = "1234567890",
                trainNumber = "12951",
                journeyDate = LocalDate.of(2026, 11, 1),
                fromStation = "NDLS",
                toStation = "BCT",
            )

        val event = CalendarEventMapper.trainEvent(ticket, emptyList(), emptyList(), strings, zone)!!

        assertThat(event.isAllDay).isTrue()
        assertThat(event.startDate).isEqualTo(LocalDate.of(2026, 11, 1))
    }

    @Test
    fun `flight maps scheduled times, designator, PNR and seat`() {
        val flight =
            FlightJourney(
                airlineIata = "6E",
                flightNumber = "2345",
                date = LocalDate.of(2026, 12, 5),
                pnrBookingRef = "AB12CD",
                seat = "14A",
                depAirport = "BLR",
                arrAirport = "DEL",
                schedDep = Instant.parse("2026-12-05T04:30:00Z"),
                schedArr = Instant.parse("2026-12-05T07:15:00Z"),
            )

        val event = CalendarEventMapper.flightEvent(flight, strings, zone)!!

        assertThat(event.summary).isEqualTo("Flight 6E 2345: BLR → DEL")
        assertThat(event.location).isEqualTo("BLR")
        // 04:30Z = 10:00 IST; 07:15Z = 12:45 IST.
        assertThat(event.startDateTime).isEqualTo(LocalDateTime.of(2026, 12, 5, 10, 0))
        assertThat(event.endDateTime).isEqualTo(LocalDateTime.of(2026, 12, 5, 12, 45))
        assertThat(event.description).isEqualTo("PNR: AB12CD\nSeat: 14A")
    }

    @Test
    fun `flight prefers estimated over scheduled times`() {
        val flight =
            FlightJourney(
                airlineIata = "AI",
                flightNumber = "0865",
                schedDep = Instant.parse("2026-12-05T04:30:00Z"),
                estDep = Instant.parse("2026-12-05T05:00:00Z"),
                schedArr = Instant.parse("2026-12-05T07:15:00Z"),
                estArr = Instant.parse("2026-12-05T07:45:00Z"),
            )

        val event = CalendarEventMapper.flightEvent(flight, strings, zone)!!

        assertThat(event.startDateTime).isEqualTo(LocalDateTime.of(2026, 12, 5, 10, 30))
        assertThat(event.endDateTime).isEqualTo(LocalDateTime.of(2026, 12, 5, 13, 15))
    }

    @Test
    fun `flight with neither times nor date is unsyncable`() {
        val flight = FlightJourney(airlineIata = "6E", flightNumber = "1", date = null)

        assertThat(CalendarEventMapper.flightEvent(flight, strings, zone)).isNull()
    }

    @Test
    fun `fingerprint changes with content and is stable otherwise`() {
        val item = ItineraryItem(tripId = "t", name = "A", date = LocalDate.of(2026, 1, 1))
        val same = CalendarEventMapper.itineraryEvent(item, strings, zone)!!
        val again = CalendarEventMapper.itineraryEvent(item, strings, zone)!!
        val changed = CalendarEventMapper.itineraryEvent(item.copy(name = "B"), strings, zone)!!

        assertThat(same.fingerprint()).isEqualTo(again.fingerprint())
        assertThat(same.fingerprint()).isNotEqualTo(changed.fingerprint())
    }
}
