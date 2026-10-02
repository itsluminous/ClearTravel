package com.itsluminous.cleartravel.feature.flights.polling

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.core.data.sync.SyncWorkStatus
import com.itsluminous.cleartravel.core.security.background.BackgroundKeyWrapper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.flights.FakeCheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.FakeFlightRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private class FakeBackgroundKeyWrapper : BackgroundKeyWrapper {
    private var key: SecretKey? = null

    override fun newEncryptCipher(): Cipher {
        val fresh = CryptoPrimitives.randomKey()
        key = fresh
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, fresh) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher? {
        val current = key ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, iv)) }
    }

    override fun deleteKey() {
        key = null
    }
}

private class InMemorySyncStateStore : BackgroundSyncStateStore {
    var hints: List<Instant> = emptyList()
    val state = MutableStateFlow(SyncWorkKind.entries.map { SyncWorkStatus(it) })

    override suspend fun flightDepartureHints(): List<Instant> = hints

    override suspend fun setFlightDepartureHints(departures: Collection<Instant>) {
        hints = departures.toList()
    }

    override val statuses = state

    override suspend fun recordDeferred(
        kind: SyncWorkKind,
        at: Instant,
    ) {
        state.value =
            state.value.map { if (it.kind == kind) it.copy(lastDeferredAt = at, deferredSinceUnlock = it.deferredSinceUnlock + 1) else it }
    }

    override suspend fun recordCompleted(
        kind: SyncWorkKind,
        at: Instant,
    ) {
        state.value = state.value.map { if (it.kind == kind) it.copy(lastCompletedAt = at) else it }
    }

    override suspend fun resetDeferredCounts() {
        state.value = state.value.map { it.copy(deferredSinceUnlock = 0) }
    }
}

/**
 * ADR-043: the poll pass against a real vault (fake Keystore) in the three states a
 * cold WorkManager process can find it in — locked without the opt-in, locked with it,
 * unlocked — plus the once-per-process nudge latch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlightPollRunnerTest {
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val keyFile = InMemoryKeyFileStore()
    private val keystore = FakeBackgroundKeyWrapper()
    private val syncStore = InMemorySyncStateStore()
    private val flights = FakeFlightRepository()
    private val posted = mutableListOf<PollNotification>()
    private val sent = mutableSetOf<String>()
    private var nudgeLatched = false
    private var nudgeCalls = 0

    private fun vault() =
        DefaultKeyVault(keyFile, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = keystore)

    private fun runner(vault: DefaultKeyVault) =
        FlightPollRunner(
            gate = BackgroundSyncGate(vault, syncStore, Clock.fixed(now, ZoneOffset.UTC)),
            stateStore = syncStore,
            flightRepository = flights,
            checkInRuleSource = FakeCheckInRuleSource(),
            sentKeys = { sent },
            markSent = { sent += it },
            post = { posted += it },
            notifyUnlockToSync = {
                nudgeCalls++
                if (nudgeLatched) false else true.also { nudgeLatched = true }
            },
            nudgeAlreadyPosted = { nudgeLatched },
            now = { now },
        )

    private suspend fun setUpVault(backgroundKey: Boolean) {
        vault().also {
            it.setUp("pw".toCharArray())
            if (backgroundKey) it.enableBackgroundUnlock("pw".toCharArray())
        }
    }

    @Test
    fun locked_noOptIn_imminentFlight_nudgesOnce_thenStaysQuiet_andNeverReChains() =
        runTest {
            setUpVault(backgroundKey = false)
            syncStore.hints = listOf(now.plus(Duration.ofHours(20)))
            val cold = vault()

            val first = runner(cold).run()
            assertThat(first).isEqualTo(FlightPollOutcome.Deferred(nudged = true, backgroundKeyEnabled = false))
            assertThat(FlightStatusWorker.resolveVerdict(first)).isEqualTo(ListenableWorker.Result.success())

            val second = runner(cold).run() // same process: latched
            assertThat(second).isEqualTo(FlightPollOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))
            assertThat(nudgeCalls).isEqualTo(1) // the policy did not even ask the second time
            assertThat(cold.isUnlocked).isFalse()
            assertThat(posted).isEmpty()
            assertThat(
                syncStore.state.value
                    .first { it.kind == SyncWorkKind.FLIGHT_POLL }
                    .deferredSinceUnlock,
            ).isEqualTo(2)
        }

    @Test
    fun locked_noOptIn_noImminentFlight_defersSilently() =
        runTest {
            setUpVault(backgroundKey = false)
            syncStore.hints = listOf(now.plus(Duration.ofDays(12)), now.minus(Duration.ofDays(2)))

            val outcome = runner(vault()).run()
            assertThat(outcome).isEqualTo(FlightPollOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))
            assertThat(nudgeCalls).isEqualTo(0)

            syncStore.hints = emptyList() // nothing known at all (fresh install, never unlocked)
            assertThat(runner(vault()).run()).isEqualTo(FlightPollOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))
            assertThat(nudgeCalls).isEqualTo(0)
        }

    @Test
    fun locked_withOptIn_selfUnlocks_runsThePass_refreshesHints_noNudge() =
        runTest {
            setUpVault(backgroundKey = true)
            val departure = now.plus(Duration.ofHours(10))
            flights.seed(Fixtures.flightJourney(schedDep = departure))
            syncStore.hints = emptyList()
            val cold = vault()

            val outcome = runner(cold).run()
            assertThat(outcome).isInstanceOf(FlightPollOutcome.Ran::class.java)
            outcome as FlightPollOutcome.Ran
            assertThat(outcome.viaBackgroundKey).isTrue()
            assertThat(outcome.posted).isEqualTo(2) // check-in open + 12h status hint
            assertThat(outcome.nextDelay).isEqualTo(Duration.ofMinutes(30))
            assertThat(posted).hasSize(2)
            assertThat(nudgeCalls).isEqualTo(0)
            assertThat(cold.isUnlocked).isTrue()
            assertThat(syncStore.hints).containsExactly(departure) // the next locked run knows
            assertThat(
                syncStore.state.value
                    .first { it.kind == SyncWorkKind.FLIGHT_POLL }
                    .lastCompletedAt,
            ).isEqualTo(now)
            assertThat(FlightStatusWorker.resolveVerdict(outcome)).isEqualTo(ListenableWorker.Result.success())
        }

    @Test
    fun locked_withOptIn_butKeyUnusable_defersWithRetry_andNeverNudges() =
        runTest {
            setUpVault(backgroundKey = true)
            keystore.deleteKey() // stands in for a screen-locked device / invalidated key
            syncStore.hints = listOf(now.plus(Duration.ofHours(2)))

            val outcome = runner(vault()).run()
            assertThat(outcome).isEqualTo(FlightPollOutcome.Deferred(nudged = false, backgroundKeyEnabled = true))
            assertThat(nudgeCalls).isEqualTo(0)
            assertThat(FlightStatusWorker.resolveVerdict(outcome)).isEqualTo(ListenableWorker.Result.retry())
        }

    @Test
    fun unlocked_runsNormally_andDedupesAcrossRuns() =
        runTest {
            val vault = vault().also { it.setUp("pw".toCharArray()) }
            flights.seed(Fixtures.flightJourney(schedDep = now.plus(Duration.ofHours(30))))

            val first = runner(vault).run() as FlightPollOutcome.Ran
            assertThat(first.viaBackgroundKey).isFalse()
            assertThat(first.posted).isEqualTo(1) // check-in open (48h window)
            val second = runner(vault).run() as FlightPollOutcome.Ran
            assertThat(second.posted).isEqualTo(0)
            assertThat(nudgeCalls).isEqualTo(0)
        }
}
