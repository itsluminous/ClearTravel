package com.itsluminous.cleartravel.feature.trains.reminder

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.sync.PnrHash
import com.itsluminous.cleartravel.core.data.sync.TrainDepartureHint
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** ADR-044 §2/§5: the pure window math and the locked-run nudge rule. */
class TrainReminderPolicyTest {
    private val departure = Instant.parse("2026-10-04T11:05:00Z")
    private val hash = PnrHash.of("8524167890")

    private fun due(
        hoursBefore: Long,
        lead: TrainReminderLead = TrainReminderLead.ONE_DAY,
        reminded: Boolean = false,
    ) = TrainReminderPolicy.isDue(departure.minus(Duration.ofHours(hoursBefore)), departure, lead, reminded)

    @Test
    fun window_opensAtLead_staysOpenUntilDeparture_closesAtDeparture() {
        assertThat(due(hoursBefore = 25)).isFalse() // too early
        assertThat(due(hoursBefore = 24)).isTrue() // exactly lead → due (inclusive)
        assertThat(due(hoursBefore = 23)).isTrue()
        assertThat(due(hoursBefore = 10)).isTrue() // added inside the window: still due
        assertThat(due(hoursBefore = 1)).isTrue()
        assertThat(due(hoursBefore = 0)).isFalse() // departure instant: no longer due (exclusive)
        assertThat(due(hoursBefore = -3)).isFalse() // departed
    }

    @Test
    fun everyLead_hasItsOwnWindow_andOffIsNeverDue() {
        assertThat(due(hoursBefore = 13, lead = TrainReminderLead.TWELVE_HOURS)).isFalse()
        assertThat(due(hoursBefore = 12, lead = TrainReminderLead.TWELVE_HOURS)).isTrue()
        assertThat(due(hoursBefore = 47, lead = TrainReminderLead.TWO_DAYS)).isTrue()
        assertThat(due(hoursBefore = 49, lead = TrainReminderLead.TWO_DAYS)).isFalse()
        assertThat(due(hoursBefore = 1, lead = TrainReminderLead.OFF)).isFalse()
    }

    @Test
    fun alreadyReminded_isNeverDueAgain_butAnotherLeadReArms() {
        assertThat(due(hoursBefore = 10, reminded = true)).isFalse()
        val remindedAt24 = setOf(TrainReminderPolicy.key(hash, TrainReminderLead.ONE_DAY))
        val hint = TrainDepartureHint(departure, hash)
        val now = departure.minus(Duration.ofHours(10))
        assertThat(TrainReminderPolicy.dueHints(now, listOf(hint), TrainReminderLead.ONE_DAY, remindedAt24)).isEmpty()
        assertThat(TrainReminderPolicy.dueHints(now, listOf(hint), TrainReminderLead.TWELVE_HOURS, remindedAt24)).containsExactly(hint)
        assertThat(TrainReminderPolicy.key(hash, TrainReminderLead.ONE_DAY)).isEqualTo("$hash:24h")
        assertThat(TrainReminderPolicy.allKeys(hash)).containsExactly("$hash:12h", "$hash:24h", "$hash:48h")
    }

    @Test
    fun shouldNudge_onlyWhenSomethingIsDue_noOptIn_notYetLatched() {
        val hints =
            listOf(
                TrainDepartureHint(departure, hash), // 10 h ahead at `now`
                TrainDepartureHint(departure.plus(Duration.ofDays(9)), PnrHash.of("1234509876")), // far out
            )
        val now = departure.minus(Duration.ofHours(10))

        fun nudge(
            lead: TrainReminderLead = TrainReminderLead.ONE_DAY,
            reminded: Set<String> = emptySet(),
            optIn: Boolean = false,
            latched: Boolean = false,
        ) = TrainReminderPolicy.shouldNudge(now, hints, lead, reminded, optIn, latched)

        assertThat(nudge()).isTrue()
        assertThat(nudge(reminded = setOf(TrainReminderPolicy.key(hash, TrainReminderLead.ONE_DAY)))).isFalse()
        assertThat(nudge(lead = TrainReminderLead.OFF)).isFalse()
        assertThat(nudge(optIn = true)).isFalse() // device merely screen-locked: retry, do not nag
        assertThat(nudge(latched = true)).isFalse() // the flight poller already nudged this process
        assertThat(TrainReminderPolicy.shouldNudge(now, emptyList(), TrainReminderLead.ONE_DAY, emptySet(), false, false)).isFalse()
        assertThat(
            TrainReminderPolicy.shouldNudge(
                departure.plus(Duration.ofHours(1)),
                hints,
                TrainReminderLead.ONE_DAY,
                emptySet(),
                false,
                false,
            ),
        ).isFalse() // departed
    }
}
