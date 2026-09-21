package com.itsluminous.cleartravel.core.database.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import com.itsluminous.cleartravel.core.testing.syncColumns
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrainDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: TrainDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.trainDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then read round-trips a ticket with converters intact`() =
        runTest {
            val ticket = Fixtures.trainTicket(id = Fixtures.FIXED_ID, lastFetchedAt = Fixtures.NOW)
            dao.upsert(ticket.toEntity())

            assertThat(dao.getById(Fixtures.FIXED_ID)).isEqualTo(ticket.toEntity())
        }

    @Test
    fun `active and archived tickets are separated`() =
        runTest {
            val active = Fixtures.trainTicket()
            val archived = Fixtures.trainTicket(archived = true)
            dao.upsert(active.toEntity())
            dao.upsert(archived.toEntity())

            assertThat(dao.observeActive().first()).containsExactly(active.toEntity())
            assertThat(dao.observeArchived().first()).containsExactly(archived.toEntity())
        }

    @Test
    fun `coaches round-trip ordered by sort order and hide tombstones`() =
        runTest {
            val ticketId = Fixtures.FIXED_ID
            val engine = Fixtures.trainCoach(ticketId = ticketId, code = "EN", sortOrder = 0)
            val sleeper = Fixtures.trainCoach(ticketId = ticketId, code = "S1", sortOrder = 1)
            val gone = Fixtures.trainCoach(ticketId = ticketId, code = "S2", sortOrder = 2, deletedAt = Fixtures.NOW)
            val other = Fixtures.trainCoach(ticketId = "other", code = "B1", sortOrder = 0)
            dao.upsertCoaches(listOf(sleeper, gone, engine, other).map { it.toEntity() })

            assertThat(dao.observeCoaches(ticketId).first())
                .containsExactly(engine.toEntity(), sleeper.toEntity())
                .inOrder()
        }

    @Test
    fun `soft-deleting coaches for a ticket tombstones only that ticket's live rows`() =
        runTest {
            val ticketId = Fixtures.FIXED_ID
            val mine = Fixtures.trainCoach(ticketId = ticketId, code = "EN")
            val other = Fixtures.trainCoach(ticketId = "other", code = "EN")
            dao.upsertCoaches(listOf(mine, other).map { it.toEntity() })
            val at = Fixtures.NOW.plusSeconds(60)

            dao.softDeleteCoachesFor(ticketId, at)

            assertThat(dao.observeCoaches(ticketId).first()).isEmpty()
            assertThat(dao.observeCoaches("other").first()).containsExactly(other.toEntity())
            val columns = db.syncColumns("train_coaches", mine.id)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(at.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(at.toEpochMilli())
        }

    @Test
    fun `passengers round-trip ordered by sort order`() =
        runTest {
            val ticketId = Fixtures.FIXED_ID
            val p2 = Fixtures.trainPassenger(ticketId = ticketId, name = "P2", sortOrder = 1)
            val p1 = Fixtures.trainPassenger(ticketId = ticketId, name = "P1", sortOrder = 0)
            dao.upsertPassengers(listOf(p2, p1).map { it.toEntity() })

            assertThat(dao.getPassengers(ticketId).map { it.name }).containsExactly("P1", "P2").inOrder()
            assertThat(dao.observePassengers(ticketId).first()).hasSize(2)
        }

    @Test
    fun `route stops round-trip and softDeleteRouteStopsFor clears them`() =
        runTest {
            val ticketId = Fixtures.FIXED_ID
            val stop = Fixtures.trainRouteStop(ticketId = ticketId)
            dao.upsertRouteStops(listOf(stop.toEntity()))
            assertThat(dao.observeRouteStops(ticketId).first()).hasSize(1)

            dao.softDeleteRouteStopsFor(ticketId, Fixtures.NOW.plusSeconds(1))
            assertThat(dao.observeRouteStops(ticketId).first()).isEmpty()
        }

    @Test
    fun `soft delete hides the ticket and bumps updated_at`() =
        runTest {
            dao.upsert(Fixtures.trainTicket(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW).toEntity())
            val deleteAt = Fixtures.NOW.plusSeconds(60)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            val columns = db.syncColumns("train_tickets", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }

    @Test
    fun `softDeletePassengersFor tombstones passengers of that ticket only`() =
        runTest {
            val mine = Fixtures.trainPassenger(ticketId = "ticket-a")
            val other = Fixtures.trainPassenger(ticketId = "ticket-b")
            dao.upsertPassengers(listOf(mine, other).map { it.toEntity() })

            dao.softDeletePassengersFor("ticket-a", Fixtures.NOW.plusSeconds(1))

            assertThat(dao.getPassengers("ticket-a")).isEmpty()
            assertThat(dao.getPassengers("ticket-b")).hasSize(1)
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlightDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: FlightDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.flightDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then read round-trips a flight with converters intact`() =
        runTest {
            val flight = Fixtures.flightJourney(id = Fixtures.FIXED_ID, lastFetchedAt = Fixtures.NOW)
            dao.upsert(flight.toEntity())

            assertThat(dao.getById(Fixtures.FIXED_ID)).isEqualTo(flight.toEntity())
            assertThat(dao.observeById(Fixtures.FIXED_ID).first()).isEqualTo(flight.toEntity())
        }

    @Test
    fun `active and archived flights are separated`() =
        runTest {
            val active = Fixtures.flightJourney()
            val archived = Fixtures.flightJourney(archived = true)
            dao.upsert(active.toEntity())
            dao.upsert(archived.toEntity())

            assertThat(dao.observeActive().first()).containsExactly(active.toEntity())
            assertThat(dao.observeArchived().first()).containsExactly(archived.toEntity())
        }

    @Test
    fun `soft delete hides the flight and bumps updated_at`() =
        runTest {
            dao.upsert(Fixtures.flightJourney(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW).toEntity())
            val deleteAt = Fixtures.NOW.plusSeconds(60)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            val columns = db.syncColumns("flight_journeys", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: AttachmentDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.attachmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `attachments are scoped to their polymorphic owner`() =
        runTest {
            val trainFile = Fixtures.attachment(ownerType = AttachmentOwnerType.TRAIN, ownerId = "t1")
            val flightFile = Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = "t1")
            dao.upsert(trainFile.toEntity())
            dao.upsert(flightFile.toEntity())

            assertThat(dao.observeForOwner(AttachmentOwnerType.TRAIN, "t1").first())
                .containsExactly(trainFile.toEntity())
        }

    @Test
    fun `pending Drive uploads are the live rows without a drive file id`() =
        runTest {
            val pending = Fixtures.attachment(driveFileId = null)
            val uploaded = Fixtures.attachment(driveFileId = "drive-123")
            dao.upsert(pending.toEntity())
            dao.upsert(uploaded.toEntity())

            assertThat(dao.getPendingDriveUploads()).containsExactly(pending.toEntity())
        }

    @Test
    fun `soft delete hides the attachment and bumps updated_at`() =
        runTest {
            val attachment = Fixtures.attachment(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW)
            dao.upsert(attachment.toEntity())
            val deleteAt = Fixtures.NOW.plusSeconds(60)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            val columns = db.syncColumns("attachments", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }

    @Test
    fun `softDeleteForOwner tombstones only that owner's attachments`() =
        runTest {
            val mine = Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = "f1")
            val other = Fixtures.attachment(ownerType = AttachmentOwnerType.FLIGHT, ownerId = "f2")
            dao.upsert(mine.toEntity())
            dao.upsert(other.toEntity())

            dao.softDeleteForOwner(AttachmentOwnerType.FLIGHT, "f1", Fixtures.NOW.plusSeconds(1))

            assertThat(dao.observeForOwner(AttachmentOwnerType.FLIGHT, "f1").first()).isEmpty()
            assertThat(dao.observeForOwner(AttachmentOwnerType.FLIGHT, "f2").first()).hasSize(1)
        }
}
