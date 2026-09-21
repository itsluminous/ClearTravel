package com.itsluminous.cleartravel.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written Room migrations for [ClearTravelDatabase]. Every database builder
 * (production, and any test opening a persisted file) MUST register [ALL] — a
 * missing migration makes Room throw on open and would lose the user's data if a
 * fallback-to-destructive were ever added instead. All migrations are ADDITIVE
 * (new tables / nullable columns only), matching the ADR-002/ADR-004 contract
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

    /** Every migration, in order — pass as `addMigrations(*DatabaseMigrations.ALL)`. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
