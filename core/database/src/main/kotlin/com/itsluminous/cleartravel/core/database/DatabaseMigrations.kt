package com.itsluminous.cleartravel.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.itsluminous.cleartravel.core.model.FlightPassengerIds

/**
 * Hand-written Room migrations for [ClearTravelDatabase]. Every database builder
 * (production, and any test opening a persisted file) MUST register [ALL] — a
 * missing migration makes Room throw on open and would lose the user's data if a
 * fallback-to-destructive were ever added instead. All migrations are ADDITIVE
 * (new tables / nullable columns / backfilled rows only), matching the ADR-002/ADR-004 contract
 * discipline; the SQL mirrors the exported `schemas/<version>.json` exactly, which
 * `MigrationTest` verifies by opening a v(N-1) file at vN.
 */
object DatabaseMigrations {
    /** v1 → v2 (ADR-022): the `train_coaches` table for coach-position data. */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `train_coaches` (" +
                        "`id` TEXT NOT NULL, `ticket_id` TEXT NOT NULL, `code` TEXT NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted_at` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_train_coaches_ticket_id` ON `train_coaches` (`ticket_id`)",
                )
            }
        }

    /** v2 → v3 (ADR-027): the `travel_documents` table for the Documents tab. */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `travel_documents` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`file_path` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `added_at` INTEGER NOT NULL, " +
                        "`expiry_date` TEXT, `note` TEXT NOT NULL, `drive_file_id` TEXT, " +
                        "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, PRIMARY KEY(`id`))",
                )
            }
        }

    /**
     * v3 → v4 (ADR-046): the `flight_passengers` table, plus ONE backfilled passenger
     * row per live flight that carried a seat or a boarding pass — so a flight imported
     * before passengers existed shows its seat/pass as passenger #1 right after the
     * upgrade. The row's id is derived from the flight id ([FlightPassengerIds.legacy])
     * so a v3-build backup imported later resolves to the SAME row instead of a second
     * passenger; `updated_at` is the flight's own timestamp (the data is as old as the
     * flight, and a backup row edited since must win the merge).
     */
    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `flight_passengers` (" +
                        "`id` TEXT NOT NULL, `flight_id` TEXT NOT NULL, `name` TEXT NOT NULL, `seat` TEXT NOT NULL, " +
                        "`pnr_booking_ref` TEXT NOT NULL, `boarding_pass_path` TEXT, `sequence_number` TEXT NOT NULL, " +
                        "`cabin_class` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "`deleted_at` INTEGER, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_flight_passengers_flight_id` ON `flight_passengers` (`flight_id`)",
                )
                db
                    .query(
                        "SELECT id, seat, boarding_pass_path, updated_at FROM flight_journeys " +
                            "WHERE deleted_at IS NULL AND (TRIM(seat) <> '' OR boarding_pass_path IS NOT NULL)",
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val flightId = cursor.getString(0)
                            db.execSQL(
                                "INSERT OR IGNORE INTO `flight_passengers` (`id`, `flight_id`, `name`, `seat`, `pnr_booking_ref`, " +
                                    "`boarding_pass_path`, `sequence_number`, `cabin_class`, `sort_order`, `updated_at`, `deleted_at`) " +
                                    "VALUES (?, ?, '', ?, '', ?, '', '', 0, ?, NULL)",
                                arrayOf<Any?>(
                                    FlightPassengerIds.legacy(flightId),
                                    flightId,
                                    cursor.getString(1).trim(),
                                    if (cursor.isNull(2)) null else cursor.getString(2),
                                    cursor.getLong(3),
                                ),
                            )
                        }
                    }
            }
        }

    /** Every migration, in order — pass as `addMigrations(*DatabaseMigrations.ALL)`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
