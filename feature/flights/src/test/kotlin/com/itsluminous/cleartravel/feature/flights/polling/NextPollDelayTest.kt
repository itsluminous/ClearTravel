package com.itsluminous.cleartravel.feature.flights.polling

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class NextPollDelayTest {
    private val now = Instant.parse("2026-09-20T12:00:00Z")

    private fun delayAt(hoursToDeparture: Long): Duration? = NextPollDelay.compute(now, now.plusSeconds(hoursToDeparture * 3600))

    @Test
    fun `more than 48h out polls every 6h`() {
        assertThat(delayAt(72)).isEqualTo(Duration.ofHours(6))
        assertThat(delayAt(49)).isEqualTo(Duration.ofHours(6))
    }

    @Test
    fun `48h to 12h out polls every 3h`() {
        assertThat(delayAt(48)).isEqualTo(Duration.ofHours(3))
        assertThat(delayAt(13)).isEqualTo(Duration.ofHours(3))
    }

    @Test
    fun `12h to 3h out polls every 30min`() {
        assertThat(delayAt(12)).isEqualTo(Duration.ofMinutes(30))
        assertThat(delayAt(4)).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `under 3h polls at the WorkManager minimum 15min`() {
        assertThat(delayAt(3)).isEqualTo(Duration.ofMinutes(15))
        assertThat(delayAt(1)).isEqualTo(Duration.ofMinutes(15))
        // Recently departed flights keep polling (gate → belt updates).
        assertThat(delayAt(-2)).isEqualTo(Duration.ofMinutes(15))
    }

    @Test
    fun `polling stops after the landing watch`() {
        assertThat(delayAt(-7)).isNull()
    }

    @Test
    fun `null departure never polls`() {
        assertThat(NextPollDelay.compute(now, null)).isNull()
    }

    @Test
    fun `computeNext picks the soonest across flights`() {
        val next =
            NextPollDelay.computeNext(
                now,
                listOf(
                    now.plusSeconds(72 * 3600),
                    now.plusSeconds(4 * 3600),
                    null,
                ),
            )

        assertThat(next).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `computeNext is null when nothing is pollable`() {
        assertThat(NextPollDelay.computeNext(now, listOf(null, now.minusSeconds(8 * 3600)))).isNull()
    }
}
