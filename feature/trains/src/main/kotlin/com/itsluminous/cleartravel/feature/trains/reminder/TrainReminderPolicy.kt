package com.itsluminous.cleartravel.feature.trains.reminder

import com.itsluminous.cleartravel.core.data.sync.TrainDepartureHint
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import java.time.Instant

/**
 * PURE decisions behind the train journey reminder (ADR-044 §2, §5).
 *
 * A ticket is **due** at [now] when `departure − lead ≤ now < departure` and its [key]
 * is not in the reminded set. The window is open up to departure on purpose: the
 * reminded set already makes the reminder fire exactly once, and an open window is
 * what lets a ticket ADDED inside the window (10 h before departure with a 24 h lead)
 * remind on the next worker run rather than never. A departed ticket is never due.
 *
 * The key is `pnrHash:lead` — changing the lead re-arms the reminder for the new
 * moment, the same lead never repeats for the same ticket.
 */
object TrainReminderPolicy {
    /** The reminded-set key for one ticket at one lead (`PnrHash.of(pnr)` + the lead's storage value). */
    fun key(
        pnrHash: String,
        lead: TrainReminderLead,
    ): String = "$pnrHash:${lead.storageValue}"

    /** True when the reminder for a ticket departing at [departure] should go out now. */
    fun isDue(
        now: Instant,
        departure: Instant,
        lead: TrainReminderLead,
        alreadyReminded: Boolean,
    ): Boolean {
        val window = lead.lead ?: return false
        if (alreadyReminded) return false
        val opens = departure.minus(window)
        return !now.isBefore(opens) && now.isBefore(departure)
    }

    /** The hints whose reminder is due — what a locked run would have posted had it had the key. */
    fun dueHints(
        now: Instant,
        hints: Collection<TrainDepartureHint>,
        lead: TrainReminderLead,
        reminded: Set<String>,
    ): List<TrainDepartureHint> =
        hints.filter { hint ->
            isDue(now, hint.departure, lead, alreadyReminded = key(hint.pnrHash, lead) in reminded)
        }

    /**
     * ADR-043 §1 / ADR-044 §5: a locked run is worth the ONE "unlock to sync" nudge only
     * when it actually had a reminder to post, never when the user opted into the
     * background key (then a refusal is the device being screen-locked — transient),
     * and never twice in one process (the latch is `AppLockNotifier.postedThisProcess`,
     * shared with the flight poller).
     */
    fun shouldNudge(
        now: Instant,
        hints: Collection<TrainDepartureHint>,
        lead: TrainReminderLead,
        reminded: Set<String>,
        backgroundKeyEnabled: Boolean,
        alreadyPostedThisProcess: Boolean,
    ): Boolean = !backgroundKeyEnabled && !alreadyPostedThisProcess && dueHints(now, hints, lead, reminded).isNotEmpty()

    /** Every key a ticket could carry — what the reminded set is pruned to on a granted run. */
    fun allKeys(pnrHash: String): List<String> = TrainReminderLead.entries.filter { it.lead != null }.map { key(pnrHash, it) }
}
