package com.itsluminous.cleartravel.feature.flights.polling

import java.time.Duration
import java.time.Instant

/**
 * PURE decision behind the "Unlock Clear Travel to sync" nudge (ADR-043, the nag fix).
 * A locked poll run is only worth interrupting the user for when it would actually
 * have had something to say: some active flight departs within [HORIZON_BEFORE] (48 h
 * — the widest check-in window in `checkin-windows.json`, so every check-in-open and
 * every 12 h / 3 h status hint falls inside it) or departed less than
 * [NextPollDelay.LANDING_WATCH] ago (the poll chain is still live for it). Everything
 * else defers silently: a flight three weeks out loses nothing by waiting for the next
 * app open. The departures come from the plaintext hint the unlocked app writes, never
 * from the (locked) database.
 *
 * Two more gates, both the caller's state: the nudge is posted at most once per process
 * (`AppLockNotifier` latches), and never when the user opted into "allow sync while
 * locked" — then a refusal means the DEVICE is screen-locked right now, a transient the
 * worker simply retries, not something the user can act on.
 */
object UnlockNudgePolicy {
    val HORIZON_BEFORE: Duration = Duration.ofHours(48)

    /** True when at least one of [departures] is imminent as defined above. */
    fun hasImminentFlight(
        now: Instant,
        departures: Collection<Instant>,
    ): Boolean {
        val earliest = now.minus(NextPollDelay.LANDING_WATCH)
        val latest = now.plus(HORIZON_BEFORE)
        return departures.any { !it.isBefore(earliest) && !it.isAfter(latest) }
    }

    fun shouldNudge(
        now: Instant,
        departures: Collection<Instant>,
        backgroundKeyEnabled: Boolean,
        alreadyPostedThisProcess: Boolean,
    ): Boolean = !backgroundKeyEnabled && !alreadyPostedThisProcess && hasImminentFlight(now, departures)
}
