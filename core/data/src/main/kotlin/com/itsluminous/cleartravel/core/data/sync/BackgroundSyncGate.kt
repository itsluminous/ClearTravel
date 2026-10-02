package com.itsluminous.cleartravel.core.data.sync

import com.itsluminous.cleartravel.core.security.vault.KeyVault
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** What a worker found when it asked for the encrypted store (ADR-043). */
sealed interface SyncAccess {
    /** The DEK is in memory — proceed. [viaBackgroundKey] when THIS call unlocked the vault with the opt-in key. */
    data class Granted(
        val viaBackgroundKey: Boolean,
    ) : SyncAccess

    /**
     * No key: defer. [backgroundKeyEnabled] tells the worker whether the user opted in
     * (the refusal is then transient — device screen-locked — and worth a retry) or not
     * (nothing will change until the user opens the app).
     */
    data class Locked(
        val backgroundKeyEnabled: Boolean,
    ) : SyncAccess
}

/**
 * The single vault gate every background job passes through (ADR-043). Order:
 * (1) already unlocked in this process → granted; (2) try the opt-in background key
 * ([KeyVault.unlockWithBackgroundKey] — a no-op false when the setting is off);
 * (3) otherwise locked. Every outcome is stamped into the [BackgroundSyncStateStore]
 * so Settings can show "last successful / skipped while locked". Deciding whether to
 * NOTIFY is the caller's business — this gate never posts anything.
 */
@Singleton
class BackgroundSyncGate
    @Inject
    constructor(
        private val keyVault: KeyVault,
        private val stateStore: BackgroundSyncStateStore,
        private val clock: Clock,
    ) {
        suspend fun open(kind: SyncWorkKind): SyncAccess {
            val now = clock.instant()
            if (keyVault.isUnlocked) {
                stateStore.recordCompleted(kind, now)
                return SyncAccess.Granted(viaBackgroundKey = false)
            }
            if (keyVault.unlockWithBackgroundKey()) {
                stateStore.recordCompleted(kind, now)
                return SyncAccess.Granted(viaBackgroundKey = true)
            }
            stateStore.recordDeferred(kind, now)
            return SyncAccess.Locked(backgroundKeyEnabled = keyVault.hasBackgroundKey)
        }
    }
