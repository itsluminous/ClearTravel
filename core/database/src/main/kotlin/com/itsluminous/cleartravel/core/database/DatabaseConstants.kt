package com.itsluminous.cleartravel.core.database

/** Shared database constants for [ClearTravelDatabase]. */
object DatabaseConstants {
    /** On-device database file name — stable across all future schema versions. */
    const val DATABASE_NAME = "cleartravel.db"

    /**
     * Current Room schema version. History: 1 = ADR-004 catalog (11 tables);
     * 2 = `train_coaches` added (ADR-022).
     */
    const val SCHEMA_VERSION = 2
}
