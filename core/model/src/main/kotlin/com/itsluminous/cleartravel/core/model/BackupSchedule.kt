package com.itsluminous.cleartravel.core.model

import java.time.Duration

/**
 * ADR-037: how often the app writes an automatic backup on its own. Persisted as
 * [storageValue] in the settings DataStore (same shape as [ThemeMode]); [period] is
 * the WorkManager repeat interval, null for [OFF].
 *
 * Every run writes the local app-storage backup; when Drive backups are enabled and
 * an account is linked the same run uploads it afterwards (the manual export chain).
 */
enum class BackupSchedule(
    val storageValue: String,
    val period: Duration?,
) {
    OFF("off", null),
    DAILY("daily", Duration.ofDays(1)),
    WEEKLY("weekly", Duration.ofDays(7)),
    MONTHLY("monthly", Duration.ofDays(30)),
    ;

    companion object {
        /** Automatic backups are opt-in: nothing runs until the user picks a cadence. */
        val DEFAULT = OFF

        /** Parses a stored value; unknown/absent values fall back to [DEFAULT]. */
        fun fromStorage(value: String?): BackupSchedule = entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
