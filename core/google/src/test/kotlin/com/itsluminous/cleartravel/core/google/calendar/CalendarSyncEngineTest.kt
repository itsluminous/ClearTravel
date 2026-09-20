package com.itsluminous.cleartravel.core.google.calendar

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.google.FakeCalendarClient
import com.itsluminous.cleartravel.core.google.FakeCalendarSyncStateStore
import com.itsluminous.cleartravel.core.google.FakeFlightRepository
import com.itsluminous.cleartravel.core.google.FakeGoogleLinkStore
import com.itsluminous.cleartravel.core.google.FakeItineraryRepository
import com.itsluminous.cleartravel.core.google.FakeTrainRepository
import com.itsluminous.cleartravel.core.google.FakeTripRepository
import com.itsluminous.cleartravel.core.google.GoogleScopes
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkSnapshot
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.model.Trip
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Full reconciliation behavior against fake clients — never live APIs. */
class CalendarSyncEngineTest {
    private lateinit var linkStore: FakeGoogleLinkStore
    private lateinit var client: FakeCalendarClient
    private lateinit var stateStore: FakeCalendarSyncStateStore
    private lateinit var trips: FakeTripRepository
    private lateinit var items: FakeItineraryRepository
    private lateinit var trains: FakeTrainRepository
    private lateinit var flights: FakeFlightRepository

    private val strings =
        CalendarEventStrings(
            trainSummary = "Train %1\$s: %2\$s → %3\$s",
            flightSummary = "Flight %1\$s: %2\$s → %3\$s",
            commuteSummary = "%1\$s → %2\$s",
            pnrLine = "PNR: %1\$s",
            seatLine = "Seat: %1\$s",
            passengerLine = "%1\$s — %2\$s (%3\$s)",
        )

    @Before
    fun setUp() {
        linkStore =
            FakeGoogleLinkStore(
                GoogleLinkSnapshot(
                    email = "traveler@example.com",
                    grantedScopes = setOf(GoogleScopes.CALENDAR_APP_CREATED),
                    calendarSyncEnabled = true,
                ),
            )
        client = FakeCalendarClient()
        stateStore = FakeCalendarSyncStateStore()
        trips = FakeTripRepository()
        items = FakeItineraryRepository()
        trains = FakeTrainRepository()
        flights = FakeFlightRepository()
    }

    private fun engine() =
        CalendarSyncEngine(
            linkStore = linkStore,
            calendarClient = client,
            stateStore = stateStore,
            tripRepository = trips,
            itineraryRepository = items,
            trainRepository = trains,
            flightRepository = flights,
            strings = strings,
            calendarName = "ClearTravel",
            zone = ZoneId.of("Asia/Kolkata"),
        )

    @Test
    fun `skips silently when sync is disabled or not linked`() =
        runTest {
            linkStore.setCalendarSyncEnabled(false)
            assertThat(engine().reconcile().isSuccess).isTrue()
            assertThat(client.createCalendarCalls).isEqualTo(0)

            linkStore.clear()
            assertThat(engine().reconcile().isSuccess).isTrue()
            assertThat(client.createCalendarCalls).isEqualTo(0)
        }

    @Test
    fun `creates the dedicated calendar exactly once across passes`() =
        runTest {
            engine().reconcile()
            engine().reconcile()

            assertThat(client.createCalendarCalls).isEqualTo(1)
            assertThat(client.calendars.values).containsExactly("ClearTravel")
            assertThat(linkStore.current().calendarId).isEqualTo("cal-1")
        }

    @Test
    fun `recreates the calendar when it disappeared server-side`() =
        runTest {
            engine().reconcile()
            client.calendars.clear()

            engine().reconcile()

            assertThat(client.createCalendarCalls).isEqualTo(2)
        }

    @Test
    fun `new rows insert events and record ids on the row and in the store`() =
        runTest {
            val trip = Trip(name = "Paris")
            val item = ItineraryItem(tripId = trip.id, name = "Louvre", date = LocalDate.of(2026, 10, 3))
            val ticket = TrainTicket(pnr = "1234567890", journeyDate = LocalDate.of(2026, 11, 1), fromStation = "NDLS", toStation = "BCT")
            val flight = FlightJourney(airlineIata = "6E", flightNumber = "1", date = LocalDate.of(2026, 12, 1))
            trips.save(trip)
            items.save(item)
            trains.save(ticket)
            flights.save(flight)

            val result = engine().reconcile()

            assertThat(result.isSuccess).isTrue()
            assertThat(client.insertCalls).isEqualTo(3)
            assertThat(items.getItem(item.id)!!.googleEventId).isNotNull()
            assertThat(trains.getTicket(ticket.id)!!.googleEventId).isNotNull()
            assertThat(flights.getFlight(flight.id)!!.googleEventId).isNotNull()
            assertThat(stateStore.all()).hasSize(3)
        }

    @Test
    fun `unchanged rows cause no HTTP on the next pass`() =
        runTest {
            val trip = Trip(name = "Paris")
            trips.save(trip)
            items.save(ItineraryItem(tripId = trip.id, name = "Louvre", date = LocalDate.of(2026, 10, 3)))
            engine().reconcile()
            val insertsAfterFirst = client.insertCalls

            engine().reconcile()

            assertThat(client.insertCalls).isEqualTo(insertsAfterFirst)
            assertThat(client.updateCalls).isEqualTo(0)
        }

    @Test
    fun `edited rows patch the recorded event`() =
        runTest {
            val trip = Trip(name = "Paris")
            val item = ItineraryItem(tripId = trip.id, name = "Louvre", date = LocalDate.of(2026, 10, 3))
            trips.save(trip)
            items.save(item)
            engine().reconcile()
            val eventId = stateStore.all().getValue(item.id).eventId

            items.save(items.getItem(item.id)!!.copy(name = "Musée du Louvre"))
            engine().reconcile()

            assertThat(client.updateCalls).isEqualTo(1)
            assertThat(
                client.events
                    .getValue(eventId)
                    .second.summary,
            ).isEqualTo("Musée du Louvre")
        }

    @Test
    fun `deleted rows remove their events`() =
        runTest {
            val flight = FlightJourney(airlineIata = "6E", flightNumber = "1", date = LocalDate.of(2026, 12, 1))
            flights.save(flight)
            engine().reconcile()
            assertThat(client.events).hasSize(1)

            flights.delete(flight.id)
            engine().reconcile()

            assertThat(client.deleteCalls).isEqualTo(1)
            assertThat(client.events).isEmpty()
            assertThat(stateStore.all()).isEmpty()
        }

    @Test
    fun `deleting a trip cascades its item events`() =
        runTest {
            val trip = Trip(name = "Paris")
            trips.save(trip)
            items.save(ItineraryItem(tripId = trip.id, name = "Louvre", date = LocalDate.of(2026, 10, 3)))
            items.save(ItineraryItem(tripId = trip.id, name = "Eiffel", date = LocalDate.of(2026, 10, 4)))
            engine().reconcile()
            assertThat(client.events).hasSize(2)

            // The repository cascade tombstones the items with the trip; the fake
            // mirrors the observable outcome (items no longer live).
            trips.delete(trip.id)
            engine().reconcile()

            assertThat(client.events).isEmpty()
        }

    @Test
    fun `rows restored with an event id are adopted, never duplicated`() =
        runTest {
            val flight =
                FlightJourney(
                    airlineIata = "6E",
                    flightNumber = "1",
                    date = LocalDate.of(2026, 12, 1),
                    googleEventId = "event-restored",
                )
            flights.save(flight)

            engine().reconcile()

            assertThat(client.insertCalls).isEqualTo(0)
            assertThat(client.updateCalls).isEqualTo(1)
            assertThat(stateStore.all().getValue(flight.id).eventId).isEqualTo("event-restored")
        }

    @Test
    fun `a client failure surfaces as failure for worker retry`() =
        runTest {
            flights.save(FlightJourney(airlineIata = "6E", flightNumber = "1", date = LocalDate.of(2026, 12, 1)))
            client.failNextInsert = IllegalStateException("http 500")

            val result = engine().reconcile()

            assertThat(result.isFailure).isTrue()
        }

    @Test
    fun `deleteCalendar removes the calendar and clears bookkeeping`() =
        runTest {
            flights.save(FlightJourney(airlineIata = "6E", flightNumber = "1", date = LocalDate.of(2026, 12, 1)))
            engine().reconcile()
            val calendarId = linkStore.current().calendarId!!

            val result = engine().deleteCalendar(calendarId)

            assertThat(result.isSuccess).isTrue()
            assertThat(client.calendars).isEmpty()
            assertThat(client.events).isEmpty()
            assertThat(stateStore.all()).isEmpty()
        }
}
