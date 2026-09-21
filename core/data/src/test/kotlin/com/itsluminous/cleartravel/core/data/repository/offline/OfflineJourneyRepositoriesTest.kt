package com.itsluminous.cleartravel.core.data.repository.offline

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.provider.TrainPassengerStatus
import com.itsluminous.cleartravel.core.data.provider.TrainStatusResult
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTrainRepositoryTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var trains: OfflineTrainRepository
    private lateinit var attachments: OfflineAttachmentRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(3600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        trains = OfflineTrainRepository(db.trainDao(), db.attachmentDao(), clock)
        attachments = OfflineAttachmentRepository(db.attachmentDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save bumps updatedAt and round-trips`() =
        runTest {
            val saved = trains.save(Fixtures.trainTicket(updatedAt = Fixtures.NOW))

            assertThat(saved.updatedAt).isEqualTo(clock.instant())
            assertThat(trains.getTicket(saved.id)).isEqualTo(saved)
        }

    @Test
    fun `findByPnr matches live tickets case and whitespace insensitively, archived included`() =
        runTest {
            val saved = trains.save(Fixtures.trainTicket(pnr = "8553674906"))
            trains.setArchived(saved.id, true)

            assertThat(trains.findByPnr("  8553674906 ")?.id).isEqualTo(saved.id)
            assertThat(trains.findByPnr("1234567890")).isNull()
            assertThat(trains.findByPnr("   ")).isNull()
        }

    @Test
    fun `findByPnr ignores tombstoned tickets so a deleted PNR can be re-added`() =
        runTest {
            val saved = trains.save(Fixtures.trainTicket(pnr = "8553674906"))
            trains.delete(saved.id)

            assertThat(trains.findByPnr("8553674906")).isNull()
        }

    @Test
    fun `applyStatusResult updates passenger statuses by position and lastFetchedAt`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            trains.savePassengers(
                listOf(
                    Fixtures.trainPassenger(ticketId = ticket.id, sortOrder = 0, currentStatus = "WL 10", coach = "", seatBerth = ""),
                    Fixtures.trainPassenger(ticketId = ticket.id, sortOrder = 1, currentStatus = "WL 11", coach = "B1", seatBerth = "12"),
                ),
            )
            val fetchedAt = Fixtures.NOW.plusSeconds(7200)

            trains.applyStatusResult(
                ticket.id,
                TrainStatusResult(
                    pnr = ticket.pnr,
                    passengers =
                        listOf(
                            TrainPassengerStatus(currentStatus = "CNF", coach = "B2", seatBerth = "32 LB"),
                            TrainPassengerStatus(currentStatus = "RAC 2"),
                        ),
                    fetchedAt = fetchedAt,
                ),
            )

            val passengers = trains.observePassengers(ticket.id).first()
            assertThat(passengers[0].currentStatus).isEqualTo("CNF")
            assertThat(passengers[0].coach).isEqualTo("B2")
            assertThat(passengers[0].seatBerth).isEqualTo("32 LB")
            assertThat(passengers[1].currentStatus).isEqualTo("RAC 2")
            // Empty coach in the result keeps the stored coach.
            assertThat(passengers[1].coach).isEqualTo("B1")
            assertThat(trains.getTicket(ticket.id)?.lastFetchedAt).isEqualTo(fetchedAt)
        }

    @Test
    fun `applyStatusResult inserts scraped passengers when the ticket has none`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            val fetchedAt = Fixtures.NOW.plusSeconds(3600)

            trains.applyStatusResult(
                ticket.id,
                TrainStatusResult(
                    pnr = ticket.pnr,
                    passengers =
                        listOf(
                            TrainPassengerStatus(currentStatus = "RAC 10", bookingStatus = "RAC 21", coach = "S1", seatBerth = "49"),
                            TrainPassengerStatus(currentStatus = "RAC 11", bookingStatus = "RAC 22"),
                        ),
                    fetchedAt = fetchedAt,
                ),
            )

            val passengers = trains.observePassengers(ticket.id).first()
            assertThat(passengers).hasSize(2)
            assertThat(passengers[0].currentStatus).isEqualTo("RAC 10")
            assertThat(passengers[0].bookingStatus).isEqualTo("RAC 21")
            assertThat(passengers[0].coach).isEqualTo("S1")
            assertThat(passengers[0].seatBerth).isEqualTo("49")
            assertThat(passengers[0].sortOrder).isEqualTo(0)
            assertThat(passengers[1].currentStatus).isEqualTo("RAC 11")
            assertThat(passengers[1].sortOrder).isEqualTo(1)
        }

    @Test
    fun `applyStatusResult appends scraped passengers beyond the existing rows`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            trains.savePassengers(
                listOf(Fixtures.trainPassenger(ticketId = ticket.id, sortOrder = 0, currentStatus = "WL 10")),
            )

            trains.applyStatusResult(
                ticket.id,
                TrainStatusResult(
                    pnr = ticket.pnr,
                    passengers =
                        listOf(
                            TrainPassengerStatus(currentStatus = "CNF"),
                            TrainPassengerStatus(currentStatus = "RAC 4", coach = "S2", seatBerth = "12"),
                        ),
                    fetchedAt = Fixtures.NOW.plusSeconds(3600),
                ),
            )

            val passengers = trains.observePassengers(ticket.id).first()
            assertThat(passengers).hasSize(2)
            assertThat(passengers[0].currentStatus).isEqualTo("CNF")
            assertThat(passengers[1].currentStatus).isEqualTo("RAC 4")
            assertThat(passengers[1].coach).isEqualTo("S2")
            assertThat(passengers[1].sortOrder).isEqualTo(1)
        }

    @Test
    fun `applyStatusResult backfills blank ticket fields and keeps user values`() =
        runTest {
            val ticket =
                trains.save(
                    Fixtures.trainTicket(
                        trainNumber = "",
                        trainName = "",
                        journeyDate = null,
                        fromStation = "",
                        toStation = "",
                        travelClass = "2A",
                    ),
                )

            trains.applyStatusResult(
                ticket.id,
                TrainStatusResult(
                    pnr = ticket.pnr,
                    passengers = listOf(TrainPassengerStatus(currentStatus = "CNF")),
                    trainNumber = "20933",
                    trainName = "DANAPUR SF EXPRESS",
                    journeyDate = java.time.LocalDate.of(2026, 9, 29),
                    fromStation = "UDN",
                    toStation = "DNR",
                    travelClass = "SL",
                    fetchedAt = Fixtures.NOW.plusSeconds(60),
                ),
            )

            val updated = trains.getTicket(ticket.id)!!
            assertThat(updated.trainNumber).isEqualTo("20933")
            assertThat(updated.trainName).isEqualTo("DANAPUR SF EXPRESS")
            assertThat(updated.journeyDate).isEqualTo(java.time.LocalDate.of(2026, 9, 29))
            assertThat(updated.fromStation).isEqualTo("UDN")
            assertThat(updated.toStation).isEqualTo("DNR")
            // User-entered class is never overwritten.
            assertThat(updated.travelClass).isEqualTo("2A")
        }

    @Test
    fun `replaceRouteStops swaps the stored route`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            trains.replaceRouteStops(ticket.id, listOf(Fixtures.trainRouteStop(stationName = "Old Stop")))

            trains.replaceRouteStops(
                ticket.id,
                listOf(
                    Fixtures.trainRouteStop(stationName = "New A", sortOrder = 0),
                    Fixtures.trainRouteStop(stationName = "New B", sortOrder = 1),
                ),
            )

            val names = trains.observeRouteStops(ticket.id).first().map { it.stationName }
            assertThat(names).containsExactly("New A", "New B").inOrder()
        }

    @Test
    fun `replaceCoaches swaps the stored composition and stamps every row`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            trains.replaceCoaches(ticket.id, listOf(Fixtures.trainCoach(code = "OLD")))

            val written =
                trains.replaceCoaches(
                    ticket.id,
                    listOf(
                        Fixtures.trainCoach(code = "EN", sortOrder = 0, ticketId = "ignored"),
                        Fixtures.trainCoach(code = "S1", sortOrder = 1, ticketId = "ignored"),
                    ),
                )

            val stored = trains.observeCoaches(ticket.id).first()
            assertThat(stored.map { it.code }).containsExactly("EN", "S1").inOrder()
            assertThat(stored.map { it.ticketId }.distinct()).containsExactly(ticket.id)
            assertThat(written.map { it.updatedAt }.distinct()).containsExactly(clock.instant())
        }

    @Test
    fun `delete cascades to passengers, route stops, coaches, and attachments`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            trains.savePassengers(listOf(Fixtures.trainPassenger(ticketId = ticket.id)))
            trains.replaceRouteStops(ticket.id, listOf(Fixtures.trainRouteStop()))
            trains.replaceCoaches(ticket.id, listOf(Fixtures.trainCoach()))
            attachments.save(Fixtures.attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = ticket.id))

            trains.delete(ticket.id)

            assertThat(trains.getTicket(ticket.id)).isNull()
            assertThat(trains.observePassengers(ticket.id).first()).isEmpty()
            assertThat(trains.observeRouteStops(ticket.id).first()).isEmpty()
            assertThat(trains.observeCoaches(ticket.id).first()).isEmpty()
            assertThat(attachments.observeForOwner(AttachmentOwnerType.TRAIN, ticket.id).first()).isEmpty()
        }

    @Test
    fun `setArchived moves the ticket to the archive`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())

            trains.setArchived(ticket.id, archived = true)

            assertThat(trains.observeActive().first()).isEmpty()
            assertThat(trains.observeArchived().first()).hasSize(1)
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineFlightRepositoryTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var flights: OfflineFlightRepository
    private lateinit var attachments: OfflineAttachmentRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(3600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        flights = OfflineFlightRepository(db.flightDao(), db.attachmentDao(), clock)
        attachments = OfflineAttachmentRepository(db.attachmentDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save bumps updatedAt and round-trips`() =
        runTest {
            val saved = flights.save(Fixtures.flightJourney(updatedAt = Fixtures.NOW))

            assertThat(saved.updatedAt).isEqualTo(clock.instant())
            assertThat(flights.getFlight(saved.id)).isEqualTo(saved)
        }

    @Test
    fun `findByFlight matches live journeys case, whitespace and leading-zero insensitively, archived included`() =
        runTest {
            val date = LocalDate.of(2026, 9, 25)
            val saved = flights.save(Fixtures.flightJourney(airlineIata = "AI", flightNumber = "0101", date = date))
            flights.setArchived(saved.id, true)

            assertThat(flights.findByFlight("ai ", " 101", date)?.id).isEqualTo(saved.id)
            assertThat(flights.findByFlight("AI", "0101", date)?.id).isEqualTo(saved.id)
            assertThat(flights.findByFlight("AI", "101", date.plusDays(1))).isNull()
            assertThat(flights.findByFlight("6E", "101", date)).isNull()
            assertThat(flights.findByFlight("AI", "1010", date)).isNull()
            assertThat(flights.findByFlight("  ", "101", date)).isNull()
        }

    @Test
    fun `findByFlight ignores tombstoned journeys so a deleted flight can be re-added`() =
        runTest {
            val date = LocalDate.of(2026, 9, 25)
            val saved = flights.save(Fixtures.flightJourney(airlineIata = "AI", flightNumber = "101", date = date))
            flights.delete(saved.id)

            assertThat(flights.findByFlight("AI", "101", date)).isNull()
        }

    @Test
    fun `applyStatusResult merges reported fields and keeps unreported ones`() =
        runTest {
            val estDep = Fixtures.NOW.plusSeconds(1800)
            val flight =
                flights.save(
                    Fixtures.flightJourney(depTerminal = "T1", depGate = "", aircraftType = "A320"),
                )
            val fetchedAt = Fixtures.NOW.plusSeconds(7200)

            flights.applyStatusResult(
                flight.id,
                FlightStatusResult(
                    status = FlightStatus.DELAYED,
                    estDep = estDep,
                    depGate = "22B",
                    fetchedAt = fetchedAt,
                ),
            )

            val stored = flights.getFlight(flight.id)!!
            assertThat(stored.status).isEqualTo(FlightStatus.DELAYED)
            assertThat(stored.estDep).isEqualTo(estDep)
            assertThat(stored.depGate).isEqualTo("22B")
            // Unreported fields keep their stored values.
            assertThat(stored.depTerminal).isEqualTo("T1")
            assertThat(stored.aircraftType).isEqualTo("A320")
            assertThat(stored.schedDep).isEqualTo(flight.schedDep)
            assertThat(stored.lastFetchedAt).isEqualTo(fetchedAt)
            assertThat(stored.updatedAt).isEqualTo(clock.instant())
        }

    @Test
    fun `delete cascades to attachments`() =
        runTest {
            val flight = flights.save(Fixtures.flightJourney())
            attachments.save(Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = flight.id))

            flights.delete(flight.id)

            assertThat(flights.getFlight(flight.id)).isNull()
            assertThat(attachments.observeForOwner(AttachmentOwnerType.FLIGHT, flight.id).first()).isEmpty()
        }

    @Test
    fun `attachment save bumps updatedAt and pending uploads exclude uploaded rows`() =
        runTest {
            val pending = attachments.save(Fixtures.attachment(driveFileId = null, updatedAt = Fixtures.NOW))
            attachments.save(Fixtures.attachment(driveFileId = "drive-1"))

            assertThat(pending.updatedAt).isEqualTo(clock.instant())
            assertThat(attachments.getPendingDriveUploads()).containsExactly(pending)
        }
}
