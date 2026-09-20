package com.itsluminous.cleartravel.feature.flights.polling

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRules
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindowSpec
import org.junit.Test
import java.time.Duration
import java.time.Instant

class FlightPollEvaluatorTest {
    private val now = Instant.parse("2026-09-20T12:00:00Z")
    private val rules =
        CheckInRules(version = 1, defaultWindow = CheckInWindowSpec(opensHoursBefore = 48, closesHoursBefore = 1))

    @Test
    fun `check-in open notification when now is inside the window`() {
        val flight = Fixtures.flightJourney(schedDep = now.plusSeconds(24 * 3600))

        val plan = FlightPollEvaluator.evaluate(listOf(flight), rules, now, alreadySent = emptySet())

        assertThat(plan.notifications.filterIsInstance<PollNotification.CheckInOpen>()).hasSize(1)
    }

    @Test
    fun `already-sent dedupe keys are filtered out`() {
        val flight = Fixtures.flightJourney(schedDep = now.plusSeconds(24 * 3600))
        val first = FlightPollEvaluator.evaluate(listOf(flight), rules, now, emptySet())

        val second =
            FlightPollEvaluator.evaluate(
                listOf(flight),
                rules,
                now,
                alreadySent = first.notifications.map { it.dedupeKey }.toSet(),
            )

        assertThat(second.notifications).isEmpty()
    }

    @Test
    fun `status-check hints fire per proximity bucket`() {
        val flight = Fixtures.flightJourney(schedDep = now.plusSeconds(2 * 3600))

        val plan = FlightPollEvaluator.evaluate(listOf(flight), rules, now, emptySet())

        val hints = plan.notifications.filterIsInstance<PollNotification.StatusCheckHint>()
        assertThat(hints.map { it.bucketHours }).containsExactly(12, 3)
        // Distinct dedupe keys — each bucket nudges at most once.
        assertThat(hints.map { it.dedupeKey }.distinct()).hasSize(2)
    }

    @Test
    fun `no hints far from departure`() {
        val flight = Fixtures.flightJourney(schedDep = now.plusSeconds(72 * 3600))

        val plan = FlightPollEvaluator.evaluate(listOf(flight), rules, now, emptySet())

        assertThat(plan.notifications.filterIsInstance<PollNotification.StatusCheckHint>()).isEmpty()
    }

    @Test
    fun `archived and deleted flights are ignored`() {
        val archived = Fixtures.flightJourney(schedDep = now.plusSeconds(3600), archived = true)
        val deleted = Fixtures.flightJourney(schedDep = now.plusSeconds(3600), deletedAt = now)

        val plan = FlightPollEvaluator.evaluate(listOf(archived, deleted), rules, now, emptySet())

        assertThat(plan.notifications).isEmpty()
        assertThat(plan.nextDelay).isNull()
    }

    @Test
    fun `next delay follows the soonest departure`() {
        val far = Fixtures.flightJourney(schedDep = now.plusSeconds(72 * 3600))
        val near = Fixtures.flightJourney(schedDep = now.plusSeconds(4 * 3600))

        val plan = FlightPollEvaluator.evaluate(listOf(far, near), rules, now, emptySet())

        assertThat(plan.nextDelay).isEqualTo(Duration.ofMinutes(30))
    }
}
