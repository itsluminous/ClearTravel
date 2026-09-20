package com.itsluminous.cleartravel.core.database.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
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
class TripDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: TripDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.tripDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then read round-trips a trip`() =
        runTest {
            val trip = Fixtures.trip(id = Fixtures.FIXED_ID)
            dao.upsert(trip.toEntity())

            assertThat(dao.getById(Fixtures.FIXED_ID)).isEqualTo(trip.toEntity())
            assertThat(dao.observeActive().first()).containsExactly(trip.toEntity())
        }

    @Test
    fun `upsert updates an existing row instead of duplicating`() =
        runTest {
            dao.upsert(Fixtures.trip(id = Fixtures.FIXED_ID, name = "Old").toEntity())
            dao.upsert(Fixtures.trip(id = Fixtures.FIXED_ID, name = "New").toEntity())

            val rows = dao.observeActive().first()
            assertThat(rows).hasSize(1)
            assertThat(rows.single().name).isEqualTo("New")
        }

    @Test
    fun `archived trips are excluded from active and vice versa`() =
        runTest {
            val active = Fixtures.trip(name = "Active")
            val archived = Fixtures.trip(name = "Archived", archived = true)
            dao.upsert(active.toEntity())
            dao.upsert(archived.toEntity())

            assertThat(dao.observeActive().first()).containsExactly(active.toEntity())
            assertThat(dao.observeArchived().first()).containsExactly(archived.toEntity())
        }

    @Test
    fun `soft delete hides the row and bumps updated_at`() =
        runTest {
            val trip = Fixtures.trip(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW)
            dao.upsert(trip.toEntity())
            val deleteAt = Fixtures.NOW.plusSeconds(60)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            assertThat(dao.observeActive().first()).isEmpty()
            assertThat(dao.observeById(Fixtures.FIXED_ID).first()).isNull()
            val columns = db.syncColumns("trips", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItineraryDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: ItineraryDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.itineraryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `items come back ordered by day then order-in-day`() =
        runTest {
            val tripId = Fixtures.FIXED_ID
            val d1i2 = Fixtures.itineraryItem(tripId = tripId, dayIndex = 1, orderInDay = 2, name = "d1i2")
            val d0i1 = Fixtures.itineraryItem(tripId = tripId, dayIndex = 0, orderInDay = 1, name = "d0i1")
            val d0i0 = Fixtures.itineraryItem(tripId = tripId, dayIndex = 0, orderInDay = 0, name = "d0i0")
            dao.upsert(listOf(d1i2, d0i1, d0i0).map { it.toEntity() })

            val names = dao.observeForTrip(tripId).first().map { it.name }
            assertThat(names).containsExactly("d0i0", "d0i1", "d1i2").inOrder()
        }

    @Test
    fun `soft delete hides the item and bumps updated_at`() =
        runTest {
            val item = Fixtures.itineraryItem(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW)
            dao.upsert(listOf(item.toEntity()))
            val deleteAt = Fixtures.NOW.plusSeconds(60)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            assertThat(dao.observeForTrip(item.tripId).first()).isEmpty()
            val columns = db.syncColumns("itinerary_items", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }

    @Test
    fun `softDeleteForTrip tombstones every item of that trip only`() =
        runTest {
            val mine = Fixtures.itineraryItem(tripId = "trip-a")
            val other = Fixtures.itineraryItem(tripId = "trip-b")
            dao.upsert(listOf(mine, other).map { it.toEntity() })

            dao.softDeleteForTrip("trip-a", Fixtures.NOW.plusSeconds(1))

            assertThat(dao.observeForTrip("trip-a").first()).isEmpty()
            assertThat(dao.observeForTrip("trip-b").first()).hasSize(1)
        }
}
