package com.itsluminous.cleartravel.core.security.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How long the app may sit in the background before the UI re-locks (ADR-031). */
enum class LockTiming(
    val storageValue: String,
    val delay: Duration?,
) {
    IMMEDIATELY("immediately", Duration.ZERO),
    ONE_MINUTE("1m", Duration.ofMinutes(1)),
    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),

    /** Only a cold start locks (the default — a process death forgets the DEK anyway). */
    NEVER("never", null),
    ;

    companion object {
        val DEFAULT = NEVER

        fun fromStorage(value: String?): LockTiming = entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

/**
 * The UI half of the app lock, deliberately separate from the key vault: [locked]
 * hides the UI behind the unlock screen, while the DEK stays cached in the vault
 * so background workers keep running. A cold start is always locked (the vault has
 * no DEK); a background→foreground return re-locks the UI when the configured
 * [LockTiming] has elapsed. Pure and clock-injected for tests.
 */
class AppLockController(
    private val clock: Clock = Clock.systemUTC(),
) {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Instant? = null

    /** Called after a successful password/biometric unlock (or by the hermetic test module). */
    fun unlock() {
        _locked.value = false
        backgroundedAt = null
    }

    fun lock() {
        _locked.value = true
    }

    /** Process moved to the background (ProcessLifecycleOwner ON_STOP). */
    fun onBackground() {
        if (!_locked.value) backgroundedAt = clock.instant()
    }

    /** Process back in the foreground: re-lock if the away time exceeded [timing]. */
    fun onForeground(timing: LockTiming) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (shouldRelock(Duration.between(since, clock.instant()), timing)) lock()
    }

    companion object {
        /** Pure decision — unit-tested. */
        fun shouldRelock(
            away: Duration,
            timing: LockTiming,
        ): Boolean {
            val delay = timing.delay ?: return false
            return away >= delay
        }
    }
}
