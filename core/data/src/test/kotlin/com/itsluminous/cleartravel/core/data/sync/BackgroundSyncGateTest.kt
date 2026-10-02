package com.itsluminous.cleartravel.core.data.sync

import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.security.background.BackgroundKeyWrapper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore stand-in: an ordinary AES key; [deviceLocked] simulates `setUnlockedDeviceRequired`. */
private class FakeBackgroundKeyWrapper : BackgroundKeyWrapper {
    private var key: SecretKey? = null
    var deviceLocked = false

    override fun newEncryptCipher(): Cipher {
        val fresh = CryptoPrimitives.randomKey()
        key = fresh
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, fresh) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher? {
        if (deviceLocked) return null
        val current = key ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, iv)) }
    }

    override fun deleteKey() {
        key = null
    }
}

/** In-memory [BackgroundSyncStateStore] recording every call. */
class RecordingSyncStateStore : BackgroundSyncStateStore {
    val calls = mutableListOf<String>()
    private var hint: Instant? = null
    private val state = MutableStateFlow(SyncWorkKind.entries.map { SyncWorkStatus(it) })

    override suspend fun nextFlightDeparture(): Instant? = hint

    override suspend fun setNextFlightDeparture(departure: Instant?) {
        hint = departure
    }

    override val statuses = state

    override suspend fun recordDeferred(
        kind: SyncWorkKind,
        at: Instant,
    ) {
        calls += "deferred:${kind.storageKey}"
        state.value =
            state.value.map {
                if (it.kind == kind) it.copy(lastDeferredAt = at, deferredSinceUnlock = it.deferredSinceUnlock + 1) else it
            }
    }

    override suspend fun recordCompleted(
        kind: SyncWorkKind,
        at: Instant,
    ) {
        calls += "completed:${kind.storageKey}"
        state.value = state.value.map { if (it.kind == kind) it.copy(lastCompletedAt = at) else it }
    }

    override suspend fun resetDeferredCounts() {
        calls += "reset"
        state.value = state.value.map { it.copy(deferredSinceUnlock = 0) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSyncGateTest {
    private val keyFile = InMemoryKeyFileStore()
    private val keystore = FakeBackgroundKeyWrapper()
    private val store = RecordingSyncStateStore()
    private val now = Instant.parse("2026-10-02T13:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun vault() =
        DefaultKeyVault(keyFile, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = keystore)

    @Test
    fun unlockedVault_isGranted_andStampsCompleted() =
        runTest {
            val vault = vault().also { it.setUp("pw".toCharArray()) }
            val access = BackgroundSyncGate(vault, store, clock).open(SyncWorkKind.CALENDAR_SYNC)
            assertThat(access).isEqualTo(SyncAccess.Granted(viaBackgroundKey = false))
            assertThat(store.calls).containsExactly("completed:calendar")
            assertThat(
                store.statuses.value
                    .first { it.kind == SyncWorkKind.CALENDAR_SYNC }
                    .lastCompletedAt,
            ).isEqualTo(now)
        }

    @Test
    fun lockedVault_noOptIn_isLocked_andStampsDeferred() =
        runTest {
            vault().setUp("pw".toCharArray())
            val cold = vault()
            val access = BackgroundSyncGate(cold, store, clock).open(SyncWorkKind.FLIGHT_POLL)
            assertThat(access).isEqualTo(SyncAccess.Locked(backgroundKeyEnabled = false))
            assertThat(cold.isUnlocked).isFalse()
            assertThat(store.calls).containsExactly("deferred:flights")
            assertThat(
                store.statuses.value
                    .first { it.kind == SyncWorkKind.FLIGHT_POLL }
                    .deferredSinceUnlock,
            ).isEqualTo(1)
        }

    @Test
    fun lockedVault_withBackgroundKey_selfUnlocks_once() =
        runTest {
            vault().also {
                it.setUp("pw".toCharArray())
                it.enableBackgroundUnlock("pw".toCharArray())
            }
            val cold = vault()
            val gate = BackgroundSyncGate(cold, store, clock)
            assertThat(gate.open(SyncWorkKind.SCHEDULED_BACKUP)).isEqualTo(SyncAccess.Granted(viaBackgroundKey = true))
            assertThat(cold.isUnlocked).isTrue()
            // The next worker in the same process finds the DEK already cached.
            assertThat(gate.open(SyncWorkKind.DRIVE_UPLOAD)).isEqualTo(SyncAccess.Granted(viaBackgroundKey = false))
            assertThat(store.calls).containsExactly("completed:backup", "completed:drive").inOrder()
        }

    @Test
    fun lockedVault_withBackgroundKey_butDeviceLocked_defersAndSaysOptInIsOn() =
        runTest {
            vault().also {
                it.setUp("pw".toCharArray())
                it.enableBackgroundUnlock("pw".toCharArray())
            }
            keystore.deviceLocked = true
            val cold = vault()
            val access = BackgroundSyncGate(cold, store, clock).open(SyncWorkKind.FLIGHT_POLL)
            assertThat(access).isEqualTo(SyncAccess.Locked(backgroundKeyEnabled = true))
            assertThat(cold.isUnlocked).isFalse()
            assertThat(store.calls).containsExactly("deferred:flights")
        }
}
