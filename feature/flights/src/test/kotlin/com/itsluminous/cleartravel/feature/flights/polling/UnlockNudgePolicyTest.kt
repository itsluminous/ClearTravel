package com.itsluminous.cleartravel.feature.flights.polling

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class UnlockNudgePolicyTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")

    @Test
    fun `imminent means within 48h ahead or 6h behind`() {
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, emptyList())).isFalse()
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, listOf(now.plus(Duration.ofHours(47)))))
            .isTrue()
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, listOf(now.plus(Duration.ofHours(48))))).isTrue() // inclusive
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, listOf(now.plus(Duration.ofHours(49))))).isFalse()
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, listOf(now.minus(Duration.ofHours(5))))).isTrue()
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, listOf(now.minus(Duration.ofHours(7))))).isFalse()
        // Any one imminent flight among far / flown ones is enough.
        val mixed = listOf(now.minus(Duration.ofDays(3)), now.plus(Duration.ofDays(20)), now.plus(Duration.ofHours(3)))
        assertThat(UnlockNudgePolicy.hasImminentFlight(now, mixed)).isTrue()
    }

    @Test
    fun `nudge needs an imminent flight, no opt-in key, and a first post in this process`() {
        val soon = listOf(now.plus(Duration.ofHours(10)))
        assertThat(UnlockNudgePolicy.shouldNudge(now, soon, backgroundKeyEnabled = false, alreadyPostedThisProcess = false)).isTrue()
        assertThat(UnlockNudgePolicy.shouldNudge(now, soon, backgroundKeyEnabled = false, alreadyPostedThisProcess = true)).isFalse()
        assertThat(UnlockNudgePolicy.shouldNudge(now, soon, backgroundKeyEnabled = true, alreadyPostedThisProcess = false)).isFalse()
        val far = listOf(now.plus(Duration.ofDays(9)))
        assertThat(UnlockNudgePolicy.shouldNudge(now, far, backgroundKeyEnabled = false, alreadyPostedThisProcess = false)).isFalse()
        assertThat(
            UnlockNudgePolicy.shouldNudge(now, emptyList(), backgroundKeyEnabled = false, alreadyPostedThisProcess = false),
        ).isFalse()
    }
}
