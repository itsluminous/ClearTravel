package com.itsluminous.cleartravel.core.database

/**
 * Shared database constants. The concrete `ClearTravelDatabase` (`@Database`) class is
 * added together with the first entity (Room rejects a database with zero entities) —
 * see the Checklist milestone. Until then this module carries the Room dependency
 * wiring and this constants holder.
 */
object DatabaseConstants {
    /** On-device database file name — stable across all future schema versions. */
    const val DATABASE_NAME = "cleartravel.db"
}
