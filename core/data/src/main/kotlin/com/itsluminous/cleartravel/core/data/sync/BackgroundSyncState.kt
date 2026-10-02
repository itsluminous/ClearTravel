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

    /** ADR-044: the periodic train journey reminder. */
    TRAIN_REMINDER("train_reminder"),
}

/**
 * ADR-044: one active train ticket as the locked worker may know it — its boarding
 * departure instant and the [PnrHash] of its PNR (never the PNR). Written by the
 * unlocked app; read while locked to decide whether a reminder is due and not yet sent.
 */
data class TrainDepartureHint(
    val departure: Instant,
    val pnrHash: String,
)

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
 * [flightDepartureHints] — the scheduled departures of the active (non-archived)
 * flights, written whenever the unlocked app sees the flight list — so the locked
 * worker can tell whether any flight is imminent enough to be worth an "unlock to
 * sync" nudge without touching the encrypted database. The whole list (not just the
 * earliest) is kept so a hint written weeks ago still answers correctly once the first
 * flight has flown. Departure instants alone reveal nothing about the journeys (no
 * airline, number, route or PNR).
 *
 * ADR-044 adds the train twins: [trainDepartureHints] (departure + hashed PNR per active
 * ticket) and [remindedTrainKeys] — the opaque keys of the reminders already posted
 * (`PnrHash` + lead), kept here rather than in Room so (a) the worker can mark and read
 * them while the vault is locked and (b) notification bookkeeping never enters the
 * backup/merge surface. [retainRemindedTrainKeys] prunes the set to the tickets that
 * still exist so it cannot grow forever.
 */
interface BackgroundSyncStateStore {
    /** Scheduled departures of the active flights the unlocked app last observed (unordered). */
    suspend fun flightDepartureHints(): List<Instant>

    suspend fun setFlightDepartureHints(departures: Collection<Instant>)

    /** Boarding departures + hashed PNRs of the active train tickets the unlocked app last observed (unordered). */
    suspend fun trainDepartureHints(): List<TrainDepartureHint>

    suspend fun setTrainDepartureHints(hints: Collection<TrainDepartureHint>)

    /** Opaque keys of the train reminders already posted (see `TrainReminderPolicy.key`). */
    suspend fun remindedTrainKeys(): Set<String>

    suspend fun addRemindedTrainKey(key: String)

    /** Drops every reminded key not in [keys] — called with the keys of the tickets that still exist. */
    suspend fun retainRemindedTrainKeys(keys: Collection<String>)

    /** All kinds, in [SyncWorkKind] order, each with its stored timestamps (defaults when never run). */
    val statuses: Flow<List<SyncWorkStatus>>

    /** A run found the vault locked: stamps [SyncWorkStatus.lastDeferredAt] and bumps the running counter. */
    suspend fun recordDeferred(
        kind: SyncWorkKind,
        at: Instant,
    )

    /** A run got past the gate: stamps [SyncWorkStatus.lastCompletedAt]. */
    suspend fun recordCompleted(
        kind: SyncWorkKind,
        at: Instant,
    )

    /**
     * The user unlocked the app: each kind's running counter becomes its displayed
     * [SyncWorkStatus.deferredSinceUnlock] and a fresh running counter starts.
     */
    suspend fun resetDeferredCounts()
}
