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
    fun `delete cascades to passengers, route stops, and attachments`() =
        runTest {
            val ticket = trains.save(Fixtures.trainTicket())
            trains.savePassengers(listOf(Fixtures.trainPassenger(ticketId = ticket.id)))
            trains.replaceRouteStops(ticket.id, listOf(Fixtures.trainRouteStop()))
            attachments.save(Fixtures.attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = ticket.id))

            trains.delete(ticket.id)

            assertThat(trains.getTicket(ticket.id)).isNull()
            assertThat(trains.observePassengers(ticket.id).first()).isEmpty()
            assertThat(trains.observeRouteStops(ticket.id).first()).isEmpty()
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
