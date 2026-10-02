package com.itsluminous.cleartravel.core.data.sync

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * The background jobs that need the encrypted store (ADR-031) and therefore may have to
 * defer while the vault is locked (ADR-043). [storageKey] is the DataStore key stem —
 * never rename a value.
 */
enum class SyncWorkKind(
    val storageKey: String,
) {
    FLIGHT_POLL("flights"),
    CALENDAR_SYNC("calendar"),
    DRIVE_UPLOAD("drive"),
    SCHEDULED_BACKUP("backup"),
}

/** Device-local bookkeeping of one job kind; timestamps only, nothing about the data. */
data class SyncWorkStatus(
    val kind: SyncWorkKind,
    /** Last run that got past the vault gate (the job itself may still have failed on network). */
    val lastCompletedAt: Instant? = null,
    /** Last run that found the vault locked and gave up for now. */
    val lastDeferredAt: Instant? = null,
    /** Deferrals since the user last unlocked the app (reset by [BackgroundSyncStateStore.resetDeferredCounts]). */
    val deferredSinceUnlock: Int = 0,
)

/**
 * ADR-043: PLAINTEXT, non-sensitive state the workers can read and write while the
 * vault is locked — a Preferences DataStore, deliberately NOT Room. Two things live
 * here: (1) the per-kind "last completed / last deferred (N times)" timestamps that
 * Settings → Security shows under *Background sync*, and (2) the flight-poll hint
 * [nextFlightDeparture] — the earliest scheduled departure among the active flights,
 * written whenever the unlocked app sees the flight list — so the locked worker can
 * tell whether there is any imminent flight worth an "unlock to sync" nudge without
 * touching the encrypted database. A departure instant alone reveals nothing about the
 * journey (no airline, number, route or PNR).
 */
interface BackgroundSyncStateStore {
    /** Earliest active-flight departure the unlocked app last observed; null = none / unknown. */
    suspend fun nextFlightDeparture(): Instant?

    suspend fun setNextFlightDeparture(departure: Instant?)

    /** All kinds, in [SyncWorkKind] order, each with its stored timestamps (defaults when never run). */
    val statuses: Flow<List<SyncWorkStatus>>

    /** A run found the vault locked: stamps [SyncWorkStatus.lastDeferredAt] and bumps the counter. */
    suspend fun recordDeferred(
        kind: SyncWorkKind,
        at: Instant,
    )

    /** A run got past the gate: stamps [SyncWorkStatus.lastCompletedAt]. */
    suspend fun recordCompleted(
        kind: SyncWorkKind,
        at: Instant,
    )

    /** The user unlocked the app: every "N times since last unlock" counter goes back to 0. */
    suspend fun resetDeferredCounts()
}
