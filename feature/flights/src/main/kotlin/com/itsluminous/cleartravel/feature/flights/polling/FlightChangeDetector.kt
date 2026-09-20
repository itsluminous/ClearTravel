package com.itsluminous.cleartravel.feature.flights.polling

import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightStatus
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindow
import java.time.Instant

/** One user-notifiable difference between two snapshots of the same flight. */
sealed interface FlightChange {
    data class GateAssigned(
        val gate: String,
    ) : FlightChange

    data class GateChanged(
        val oldGate: String,
        val newGate: String,
    ) : FlightChange

    data class Delayed(
        val newEstimatedDeparture: Instant?,
    ) : FlightChange

    data object Cancelled : FlightChange

    data class BeltAssigned(
        val belt: String,
    ) : FlightChange
}

/**
 * PURE old-vs-new diff of one flight (spec feature 2 notification types). Fed by any
 * refresh path — the interactive scrape flow and (if a headless variant ever lands)
 * background polling — and mapped 1:1 onto `FlightNotifier` calls.
 */
object FlightChangeDetector {
    fun detect(
        old: FlightJourney,
        new: FlightJourney,
    ): List<FlightChange> =
        buildList {
            detectGate(old, new)?.let(::add)
            detectDelay(old, new)?.let(::add)
            if (new.status == FlightStatus.CANCELLED && old.status != FlightStatus.CANCELLED) {
                add(FlightChange.Cancelled)
            }
            if (new.baggageBelt.isNotBlank() && old.baggageBelt != new.baggageBelt) {
                add(FlightChange.BeltAssigned(new.baggageBelt))
            }
        }

    private fun detectGate(
        old: FlightJourney,
        new: FlightJourney,
    ): FlightChange? {
        if (new.depGate.isBlank() || new.depGate == old.depGate) return null
        return if (old.depGate.isBlank()) {
            FlightChange.GateAssigned(new.depGate)
        } else {
            FlightChange.GateChanged(oldGate = old.depGate, newGate = new.depGate)
        }
    }

    private fun detectDelay(
        old: FlightJourney,
        new: FlightJourney,
    ): FlightChange? {
        val becameDelayed = new.status == FlightStatus.DELAYED && old.status != FlightStatus.DELAYED
        val estimateSlipped =
            new.estDep != null &&
                new.estDep != old.estDep &&
                new.schedDep != null &&
                new.estDep!!.isAfter(new.schedDep)
        return if (becameDelayed || estimateSlipped) FlightChange.Delayed(new.estDep) else null
    }

    /**
     * PURE check-in window crossing: true exactly once — when the window opened
     * between the previous evaluation ([lastCheckedAt], exclusive) and [now]
     * (inclusive). A null [lastCheckedAt] (first evaluation) reports an already-open,
     * not-yet-closed window so a freshly added flight still gets its reminder.
     */
    fun checkInOpened(
        window: CheckInWindow?,
        lastCheckedAt: Instant?,
        now: Instant,
    ): Boolean {
        if (window == null) return false
        if (!window.isOpenAt(now)) return false
        return lastCheckedAt == null || lastCheckedAt.isBefore(window.opensAt)
    }
}
