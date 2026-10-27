package com.itsluminous.cleartravel.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.core.model.FlightPassengerIds
import com.itsluminous.cleartravel.core.testing.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves every hand-written migration in [DatabaseMigrations] against the exported
 * schema history (ADR-022): a real v1 database file — populated with a ticket, a
 * passenger and a route stop — opens at the current version with the data intact,
 * and the freshly created `train_coaches` (v2) and `travel_documents` (v3) tables are
 * usable; a real v2 file with a coach row migrates to v3 likewise; a real v3 file with
 * seated flights migrates to v4 with one backfilled passenger each (ADR-046). `MigrationTestHelper`
 * validates the migrated schema against `schemas/<version>.json` exactly, so a
 * migration whose SQL drifts from the entity definition fails here, not on a
 * user's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ClearTravelDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun `v1 database opens at the current version with existing rows intact`() =
        runTest {
            val ticket = Fixtures.trainTicket(id = Fixtures.FIXED_ID, lastFetchedAt = Fixtures.NOW)
            val passenger = Fixtures.trainPassenger(ticketId = ticket.id)
            val stop = Fixtures.trainRouteStop(ticketId = ticket.id)

            helper.createDatabase(DB_NAME, 1).use { v1 ->
                v1.execSQL(
                    "INSERT INTO train_tickets (id, pnr, train_number, train_name, journey_date, from_station, " +
                        "to_station, travel_class, quota, archived, google_event_id, last_fetched_at, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, NULL)",
                    arrayOf<Any?>(
                        ticket.id,
                        ticket.pnr,
                        ticket.trainNumber,
                        ticket.trainName,
                        ticket.journeyDate.toString(),
                        ticket.fromStation,
                        ticket.toStation,
                        ticket.travelClass,
                        ticket.quota,
                        Fixtures.NOW.toEpochMilli(),
                        Fixtures.NOW.toEpochMilli(),
                    ),
                )
                v1.execSQL(
                    "INSERT INTO train_passengers (id, ticket_id, name, coach, seat_berth, booking_status, " +
                        "current_status, sort_order, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, NULL)",
                    arrayOf<Any?>(
                        passenger.id,
                        ticket.id,
                        passenger.name,
                        passenger.coach,
                        passenger.seatBerth,
                        passenger.bookingStatus,
                        passenger.currentStatus,
                        Fixtures.NOW.toEpochMilli(),
                    ),
                )
                v1.execSQL(
                    "INSERT INTO train_route_stops (id, ticket_id, station_name, arrival, departure, platform, day, " +
                        "sort_order, updated_at, deleted_at) VALUES (?, ?, ?, ?, ?, ?, 1, 0, ?, NULL)",
                    arrayOf<Any?>(
                        stop.id,
                        ticket.id,
                        stop.stationName,
                        stop.arrival,
                        stop.departure,
                        stop.platform,
                        Fixtures.NOW.toEpochMilli(),
                    ),
                )
            }

            // Validates the migrated schema against schemas/<current>.json.
            helper.runMigrationsAndValidate(DB_NAME, DatabaseConstants.SCHEMA_VERSION, true, *DatabaseMigrations.ALL).close()

            val db =
                Room
                    .databaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        ClearTravelDatabase::class.java,
                        DB_NAME,
                    ).addMigrations(*DatabaseMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val dao = db.trainDao()
                assertThat(dao.getById(ticket.id)).isEqualTo(ticket.toEntity())
                assertThat(dao.getPassengers(ticket.id)).containsExactly(passenger.toEntity())
                assertThat(dao.observeRouteStops(ticket.id).first()).containsExactly(stop.toEntity())
                assertThat(dao.observeCoaches(ticket.id).first()).isEmpty()

                val coach = Fixtures.trainCoach(ticketId = ticket.id, code = "EN")
                dao.upsertCoaches(listOf(coach.toEntity()))
                assertThat(dao.observeCoaches(ticket.id).first()).containsExactly(coach.toEntity())

                val documentDao = db.travelDocumentDao()
                assertThat(documentDao.observeAll().first()).isEmpty()
                val document = Fixtures.travelDocument()
                documentDao.upsert(document.toEntity())
                assertThat(documentDao.observeAll().first()).containsExactly(document.toEntity())
            } finally {
                db.close()
            }
        }

    @Test
    fun `v2 database migrates to v3 keeping coaches and gaining travel_documents`() =
        runTest {
            val coach = Fixtures.trainCoach(ticketId = Fixtures.FIXED_ID, code = "B4", sortOrder = 3)
            helper.createDatabase(DB_NAME, 2).use { v2 ->
                v2.execSQL(
                    "INSERT INTO train_coaches (id, ticket_id, code, sort_order, updated_at, deleted_at) " +
                        "VALUES (?, ?, ?, ?, ?, NULL)",
                    arrayOf<Any?>(coach.id, coach.ticketId, coach.code, coach.sortOrder, Fixtures.NOW.toEpochMilli()),
                )
            }

            helper.runMigrationsAndValidate(DB_NAME, DatabaseConstants.SCHEMA_VERSION, true, *DatabaseMigrations.ALL).close()

            val db =
                Room
                    .databaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        ClearTravelDatabase::class.java,
                        DB_NAME,
                    ).addMigrations(*DatabaseMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertThat(db.trainDao().observeCoaches(Fixtures.FIXED_ID).first()).containsExactly(coach.toEntity())
                val document = Fixtures.travelDocument(expiryDate = Fixtures.TODAY.plusYears(5))
                db.travelDocumentDao().upsert(document.toEntity())
                assertThat(db.travelDocumentDao().getById(document.id)).isEqualTo(document.toEntity())
                assertThat(db.travelDocumentDao().getPendingDriveUploads()).containsExactly(document.toEntity())
            } finally {
                db.close()
            }
        }

    @Test
    fun `v3 database migrates to v4 backfilling one passenger per flight with a seat or pass`() =
        runTest {
            val seated = Fixtures.flightJourney(id = "seated", seat = " 12A ", boardingPassPath = "/data/passes/seated.pdf")
            val passOnly = Fixtures.flightJourney(id = "pass-only", seat = "", boardingPassPath = "/data/passes/pass-only.png")
            val bare = Fixtures.flightJourney(id = "bare", seat = "", boardingPassPath = null)
            val deleted = Fixtures.flightJourney(id = "deleted", seat = "1A", deletedAt = Fixtures.NOW)
            helper.createDatabase(DB_NAME, 3).use { v3 ->
                for (flight in listOf(seated, passOnly, bare, deleted)) {
                    v3.execSQL(
                        "INSERT INTO flight_journeys (id, airline_iata, flight_number, date, pnr_booking_ref, seat, cabin_class, " +
                            "dep_airport, arr_airport, sched_dep, sched_arr, est_dep, est_arr, status, dep_terminal, dep_gate, " +
                            "arr_terminal, arr_gate, baggage_belt, aircraft_type, archived, last_fetched_at, boarding_pass_path, " +
                            "check_in_url, google_event_id, updated_at, deleted_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, 'scheduled', '', '', '', '', '', '', 0, NULL, ?, " +
                            "NULL, NULL, ?, ?)",
                        arrayOf<Any?>(
                            flight.id,
                            flight.airlineIata,
                            flight.flightNumber,
                            flight.date.toString(),
                            flight.pnrBookingRef,
                            flight.seat,
                            flight.cabinClass,
                            flight.depAirport,
                            flight.arrAirport,
                            flight.boardingPassPath,
                            Fixtures.NOW.toEpochMilli(),
                            flight.deletedAt?.toEpochMilli(),
                        ),
                    )
                }
            }

            helper.runMigrationsAndValidate(DB_NAME, DatabaseConstants.SCHEMA_VERSION, true, *DatabaseMigrations.ALL).close()

            val db =
                Room
                    .databaseBuilder(
                        ApplicationProvider.getApplicationContext(),
                        ClearTravelDatabase::class.java,
                        DB_NAME,
                    ).addMigrations(*DatabaseMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            try {
                val dao = db.flightDao()
                assertThat(dao.getPassengers(seated.id)).containsExactly(
                    FlightPassenger(
                        id = FlightPassengerIds.legacy(seated.id),
                        flightId = seated.id,
                        seat = "12A",
                        boardingPassPath = seated.boardingPassPath,
                        updatedAt = Fixtures.NOW,
                    ).toEntity(),
                )
                assertThat(dao.getPassengers(passOnly.id).single().boardingPassPath).isEqualTo(passOnly.boardingPassPath)
                assertThat(dao.getPassengers(passOnly.id).single().seat).isEmpty()
                assertThat(dao.getPassengers(bare.id)).isEmpty()
                assertThat(dao.getPassengers(deleted.id)).isEmpty()
                // The flight rows themselves are untouched (mirror columns still read as before).
                assertThat(dao.getById(seated.id)?.seat).isEqualTo(" 12A ")
                assertThat(
                    dao
                        .observeActiveWithPassengers()
                        .first()
                        .map { it.toModel() }
                        .single { it.flight.id == seated.id }
                        .seats,
                ).containsExactly("12A")
            } finally {
                db.close()
            }
        }

    @Test
    fun `migration list covers every version step up to the current schema`() {
        val steps = DatabaseMigrations.ALL.map { it.startVersion to it.endVersion }
        assertThat(steps).isEqualTo((1 until DatabaseConstants.SCHEMA_VERSION).map { it to it + 1 })
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
