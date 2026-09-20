package com.itsluminous.cleartravel.feature.flights.polling

import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRules
import com.itsluminous.cleartravel.feature.flights.checkin.computeCheckInWindow
import java.time.Duration
import java.time.Instant

/** A notification the poll pass decided to send, with its idempotence [dedupeKey]. */
sealed interface PollNotification {
    val flight: FlightJourney
    val dedupeKey: String

    data class CheckInOpen(
        override val flight: FlightJourney,
    ) : PollNotification {
        override val dedupeKey: String = "${flight.id}:checkin"
    }

    /**
     * Spec-sanctioned polling fallback: without a headless scrape the app cannot know
     * the new status, so nudge "status may have changed — tap to check" as departure
     * nears. At most once per bucket per flight (the dedupe key embeds the bucket).
     */
    data class StatusCheckHint(
        override val flight: FlightJourney,
        val bucketHours: Int,
    ) : PollNotification {
        override val dedupeKey: String = "${flight.id}:hint$bucketHours"
    }
}

/** Result of one poll pass: notifications to post + when to poll again. */
data class PollPlan(
    val notifications: List<PollNotification>,
    val nextDelay: Duration?,
)

/**
 * The PURE brain of [FlightStatusWorker] (ADR-013): given the active flights, the
 * check-in rules, "now" and the set of already-sent dedupe keys, decides what to
 * notify and when to run next. The worker itself stays thin (load → evaluate →
 * post → mark → reschedule) and needs no unit test of its own.
 */
object FlightPollEvaluator {
    /** Departure proximities (hours) at which a status-check hint is worth one nudge. */
    private val HINT_BUCKETS_HOURS = listOf(12, 3)

    fun evaluate(
        flights: List<FlightJourney>,
        rules: CheckInRules,
        now: Instant,
        alreadySent: Set<String>,
    ): PollPlan {
        val candidates = flights.filter { !it.archived && it.deletedAt == null }
        val notifications = mutableListOf<PollNotification>()
        for (flight in candidates) {
            val window = computeCheckInWindow(rules, flight.airlineIata, flight.schedDep)
            if (window != null && window.isOpenAt(now)) {
                notifications += PollNotification.CheckInOpen(flight)
            }
            notifications += statusHints(flight, now)
        }
        return PollPlan(
            notifications = notifications.filter { it.dedupeKey !in alreadySent },
            nextDelay = NextPollDelay.computeNext(now, candidates.map { it.schedDep }),
        )
    }

    private fun statusHints(
        flight: FlightJourney,
        now: Instant,
    ): List<PollNotification> {
        val departure = flight.schedDep ?: return emptyList()
        if (!departure.isAfter(now)) return emptyList()
        val untilDeparture = Duration.between(now, departure)
        return HINT_BUCKETS_HOURS
            .filter { untilDeparture <= Duration.ofHours(it.toLong()) }
            .map { PollNotification.StatusCheckHint(flight, bucketHours = it) }
    }
}
