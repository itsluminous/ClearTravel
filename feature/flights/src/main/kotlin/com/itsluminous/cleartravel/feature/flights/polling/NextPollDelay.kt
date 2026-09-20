package com.itsluminous.cleartravel.feature.flights.polling

import java.time.Duration
import java.time.Instant

/**
 * PURE escalating poll cadence (spec feature 2): how long until the next background
 * poll for a flight departing at [scheduledDeparture], evaluated at [now].
 *
 * Buckets (time until departure → delay): more than 48h → 6h; 48h..12h → 3h;
 * 12h..3h → 30min; 3h before departure through [LANDING_WATCH] after → 15min
 * (WorkManager's minimum); null once the flight is [LANDING_WATCH] past departure
 * (no more polling — baggage-belt updates stop mattering a few hours after landing).
 */
object NextPollDelay {
    /** Keep polling this long past departure (gate → in-air → belt updates). */
    val LANDING_WATCH: Duration = Duration.ofHours(6)

    val MIN_PERIOD: Duration = Duration.ofMinutes(15)

    fun compute(
        now: Instant,
        scheduledDeparture: Instant?,
    ): Duration? {
        if (scheduledDeparture == null) return null
        val untilDeparture = Duration.between(now, scheduledDeparture)
        return when {
            untilDeparture < LANDING_WATCH.negated() -> null
            untilDeparture > Duration.ofHours(48) -> Duration.ofHours(6)
            untilDeparture > Duration.ofHours(12) -> Duration.ofHours(3)
            untilDeparture > Duration.ofHours(3) -> Duration.ofMinutes(30)
            else -> MIN_PERIOD
        }
    }

    /** The soonest delay across [departures]; null when nothing is worth polling. */
    fun computeNext(
        now: Instant,
        departures: List<Instant?>,
    ): Duration? = departures.mapNotNull { compute(now, it) }.minOrNull()
}
