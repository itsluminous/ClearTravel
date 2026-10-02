package com.itsluminous.cleartravel.feature.flights.polling

import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.SyncAccess
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

/** What one poll pass did — [FlightStatusWorker] maps it to a WorkManager verdict. */
sealed interface FlightPollOutcome {
    /**
     * The vault was locked. [nudged] = the "unlock to sync" notification was posted on
     * this run; [backgroundKeyEnabled] = the user opted into ADR-043 (so the refusal is
     * the DEVICE being screen-locked — transient, worth a retry).
     */
    data class Deferred(
        val nudged: Boolean,
        val backgroundKeyEnabled: Boolean,
    ) : FlightPollOutcome

    /** The pass ran: [posted] notifications sent, [nextDelay] = when to poll again (null = chain ends). */
    data class Ran(
        val posted: Int,
        val nextDelay: Duration?,
        val viaBackgroundKey: Boolean,
    ) : FlightPollOutcome
}

/**
 * One background flight-poll pass, kept free of WorkManager/Hilt so it runs against
 * fakes (the ADR-037 runner pattern). Order: (1) pass the [BackgroundSyncGate]; when
 * locked, consult the plaintext departure hints and [UnlockNudgePolicy] and post the
 * nudge through [notifyUnlockToSync] only when it says so; (2) when granted, load the
 * active flights, refresh the departure hints for the NEXT locked run, evaluate
 * ([FlightPollEvaluator]), post, mark sent.
 */
class FlightPollRunner(
    private val gate: BackgroundSyncGate,
    private val stateStore: BackgroundSyncStateStore,
    private val flightRepository: FlightRepository,
    private val checkInRuleSource: CheckInRuleSource,
    private val sentKeys: () -> Set<String>,
    private val markSent: (String) -> Unit,
    private val post: (PollNotification) -> Unit,
    /** Posts the ADR-031 nudge; returns true when it actually went out (once per process). */
    private val notifyUnlockToSync: () -> Boolean,
    private val nudgeAlreadyPosted: () -> Boolean,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun run(): FlightPollOutcome {
        val access = gate.open(SyncWorkKind.FLIGHT_POLL)
        if (access is SyncAccess.Locked) {
            val nudge =
                UnlockNudgePolicy.shouldNudge(
                    now = now(),
                    departures = stateStore.flightDepartureHints(),
                    backgroundKeyEnabled = access.backgroundKeyEnabled,
                    alreadyPostedThisProcess = nudgeAlreadyPosted(),
                )
            val posted = nudge && notifyUnlockToSync()
            return FlightPollOutcome.Deferred(nudged = posted, backgroundKeyEnabled = access.backgroundKeyEnabled)
        }
        val granted = access as SyncAccess.Granted
        val flights = flightRepository.observeActive().first()
        stateStore.setFlightDepartureHints(flights.mapNotNull { it.schedDep })
        val plan =
            FlightPollEvaluator.evaluate(
                flights = flights,
                rules = checkInRuleSource.load(),
                now = now(),
                alreadySent = sentKeys(),
            )
        for (notification in plan.notifications) {
            post(notification)
            markSent(notification.dedupeKey)
        }
        return FlightPollOutcome.Ran(
            posted = plan.notifications.size,
            nextDelay = plan.nextDelay,
            viaBackgroundKey = granted.viaBackgroundKey,
        )
    }
}
