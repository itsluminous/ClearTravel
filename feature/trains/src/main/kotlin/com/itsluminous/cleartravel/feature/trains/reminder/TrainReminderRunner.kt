package com.itsluminous.cleartravel.feature.trains.reminder

import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.SyncAccess
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import com.itsluminous.cleartravel.core.notifications.TrainReminderContent
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId

/** What one reminder pass did — `TrainReminderWorker` maps it to a WorkManager verdict. */
sealed interface TrainReminderOutcome {
    /** The vault was locked. [nudged] = the shared "unlock to sync" nudge went out on this run. */
    data class Deferred(
        val nudged: Boolean,
        val backgroundKeyEnabled: Boolean,
    ) : TrainReminderOutcome

    /** The pass ran: [posted] reminders sent under [lead] (0 when the lead is OFF or nothing was due). */
    data class Ran(
        val posted: Int,
        val lead: TrainReminderLead,
        val viaBackgroundKey: Boolean,
    ) : TrainReminderOutcome
}

/**
 * One train-reminder pass (ADR-044), free of WorkManager/Hilt so it runs against fakes
 * (the ADR-037/043 runner pattern). Order: (1) pass the [BackgroundSyncGate]; when
 * locked, evaluate [TrainReminderPolicy] over the PLAINTEXT hints + lead and post the
 * shared nudge through [notifyUnlockToSync] only when a reminder is actually due;
 * (2) when granted, derive every active ticket's departure, refresh the hints for the
 * next locked run, then post + mark the due ones. The lead is read on every run (the
 * user may change it between runs); OFF refreshes the hints and posts nothing.
 */
class TrainReminderRunner(
    private val gate: BackgroundSyncGate,
    private val stateStore: BackgroundSyncStateStore,
    private val trainRepository: TrainRepository,
    private val settingsRepository: SettingsRepository,
    private val post: (TrainReminderContent) -> Unit,
    /** Posts the ADR-043 nudge; returns true when it actually went out (once per process, shared latch). */
    private val notifyUnlockToSync: () -> Boolean,
    private val nudgeAlreadyPosted: () -> Boolean,
    private val fallbackLabel: (pnr: String) -> String,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun run(): TrainReminderOutcome {
        val lead = settingsRepository.trainReminderLead.first()
        val access = gate.open(SyncWorkKind.TRAIN_REMINDER)
        if (access is SyncAccess.Locked) {
            val nudge =
                TrainReminderPolicy.shouldNudge(
                    now = now(),
                    hints = stateStore.trainDepartureHints(),
                    lead = lead,
                    reminded = stateStore.remindedTrainKeys(),
                    backgroundKeyEnabled = access.backgroundKeyEnabled,
                    alreadyPostedThisProcess = nudgeAlreadyPosted(),
                )
            val posted = nudge && notifyUnlockToSync()
            return TrainReminderOutcome.Deferred(nudged = posted, backgroundKeyEnabled = access.backgroundKeyEnabled)
        }
        val granted = access as SyncAccess.Granted
        val candidates = TrainReminderHints.refresh(trainRepository, stateStore, zone)
        if (lead.lead == null) return TrainReminderOutcome.Ran(posted = 0, lead = lead, viaBackgroundKey = granted.viaBackgroundKey)
        val reminded = stateStore.remindedTrainKeys()
        val current = now()
        var posted = 0
        for (candidate in candidates) {
            val key = TrainReminderPolicy.key(candidate.pnrHash, lead)
            if (!TrainReminderPolicy.isDue(current, candidate.departure.instant, lead, alreadyReminded = key in reminded)) continue
            val passengers = trainRepository.observePassengers(candidate.ticket.id).first()
            post(TrainReminderContentBuilder.build(candidate.ticket, passengers, candidate.departure, current, zone, fallbackLabel))
            stateStore.addRemindedTrainKey(key)
            posted++
        }
        return TrainReminderOutcome.Ran(posted = posted, lead = lead, viaBackgroundKey = granted.viaBackgroundKey)
    }
}
