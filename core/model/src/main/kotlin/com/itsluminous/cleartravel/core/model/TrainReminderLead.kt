package com.itsluminous.cleartravel.core.model

import java.time.Duration

/**
 * ADR-044: how long before a train's boarding-station departure the journey reminder
 * ("tap to check PNR & seat status") is posted. Persisted as [storageValue] in the
 * settings DataStore (same shape as [BackupSchedule]); [lead] is null for [OFF].
 *
 * The reminder exists because the PNR status cannot be refreshed in the background
 * (the IRCTC captcha needs the user), so the app reminds the user to run the check
 * while there is still time to act on a RAC / waitlisted berth.
 */
enum class TrainReminderLead(
    val storageValue: String,
    val lead: Duration?,
) {
    OFF("off", null),
    TWELVE_HOURS("12h", Duration.ofHours(12)),
    ONE_DAY("24h", Duration.ofHours(24)),
    TWO_DAYS("48h", Duration.ofHours(48)),
    ;

    companion object {
        /** On by default: a day ahead is when the chart is still open and a status check pays off. */
        val DEFAULT = ONE_DAY

        /** Parses a stored value; unknown/absent values fall back to [DEFAULT]. */
        fun fromStorage(value: String?): TrainReminderLead = entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
