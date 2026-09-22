package com.itsluminous.cleartravel.core.security.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AppLockControllerTest {
    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-09-22T06:00:00Z"))
    private val controller = AppLockController(clock)

    @Test
    fun coldStart_isLocked_untilUnlock() {
        assertThat(controller.locked.value).isTrue()
        controller.unlock()
        assertThat(controller.locked.value).isFalse()
    }

    @Test
    fun never_onlyColdStartLocks() {
        controller.unlock()
        controller.onBackground()
        clock.now += Duration.ofDays(3)
        controller.onForeground(LockTiming.NEVER)
        assertThat(controller.locked.value).isFalse()
    }

    @Test
    fun timing_relocksOnlyAfterTheDelay() {
        controller.unlock()
        controller.onBackground()
        clock.now += Duration.ofSeconds(59)
        controller.onForeground(LockTiming.ONE_MINUTE)
        assertThat(controller.locked.value).isFalse()

        controller.onBackground()
        clock.now += Duration.ofSeconds(61)
        controller.onForeground(LockTiming.ONE_MINUTE)
        assertThat(controller.locked.value).isTrue()
    }

    @Test
    fun immediately_relocksOnAnyReturn() {
        controller.unlock()
        controller.onBackground()
        controller.onForeground(LockTiming.IMMEDIATELY)
        assertThat(controller.locked.value).isTrue()
    }

    @Test
    fun foregroundWithoutBackground_isANoOp() {
        controller.unlock()
        controller.onForeground(LockTiming.IMMEDIATELY)
        assertThat(controller.locked.value).isFalse()
    }

    @Test
    fun lockTiming_storageRoundTrip_unknownFallsBackToDefault() {
        for (timing in LockTiming.entries) assertThat(LockTiming.fromStorage(timing.storageValue)).isEqualTo(timing)
        assertThat(LockTiming.fromStorage("garbage")).isEqualTo(LockTiming.DEFAULT)
        assertThat(LockTiming.fromStorage(null)).isEqualTo(LockTiming.ONE_MINUTE)
    }

    @Test
    fun securesWindow_onlyForImmediately() {
        assertThat(LockTiming.IMMEDIATELY.securesWindow).isTrue()
        assertThat(LockTiming.entries.filter { it.securesWindow }).containsExactly(LockTiming.IMMEDIATELY)
    }
}
