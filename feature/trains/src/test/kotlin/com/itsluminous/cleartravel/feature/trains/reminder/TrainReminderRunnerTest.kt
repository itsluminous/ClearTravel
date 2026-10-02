package com.itsluminous.cleartravel.feature.trains.reminder

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncGate
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.PnrHash
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.core.data.sync.SyncWorkStatus
import com.itsluminous.cleartravel.core.data.sync.TrainDepartureHint
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import com.itsluminous.cleartravel.core.notifications.TrainReminderContent
import com.itsluminous.cleartravel.core.security.background.BackgroundKeyWrapper
import com.itsluminous.cleartravel.core.security.crypto.CryptoPrimitives
import com.itsluminous.cleartravel.core.security.vault.DefaultKeyVault
import com.itsluminous.cleartravel.core.security.vault.InMemoryKeyFileStore
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.feature.trains.FakeSettingsRepository
import com.itsluminous.cleartravel.feature.trains.FakeTrainRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
    var flightHints: List<Instant> = emptyList()
    var trainHints: List<TrainDepartureHint> = emptyList()
    val reminded = mutableSetOf<String>()
    val state = MutableStateFlow(SyncWorkKind.entries.map { SyncWorkStatus(it) })
    val pending = mutableMapOf<SyncWorkKind, Int>()

    override suspend fun flightDepartureHints(): List<Instant> = flightHints

    override suspend fun setFlightDepartureHints(departures: Collection<Instant>) {
        flightHints = departures.toList()
    }

    override suspend fun trainDepartureHints(): List<TrainDepartureHint> = trainHints

    override suspend fun setTrainDepartureHints(hints: Collection<TrainDepartureHint>) {
        trainHints = hints.toList()
    }

    override suspend fun remindedTrainKeys(): Set<String> = reminded.toSet()

    override suspend fun addRemindedTrainKey(key: String) {
        reminded += key
    }

    override suspend fun retainRemindedTrainKeys(keys: Collection<String>) {
        reminded.retainAll(keys.toSet())
    }

    override val statuses = state

    override suspend fun recordDeferred(
        kind: SyncWorkKind,
        at: Instant,
    ) {
        pending[kind] = (pending[kind] ?: 0) + 1
        state.value = state.value.map { if (it.kind == kind) it.copy(lastDeferredAt = at) else it }
    }

    override suspend fun recordCompleted(
        kind: SyncWorkKind,
        at: Instant,
    ) {
        state.value = state.value.map { if (it.kind == kind) it.copy(lastCompletedAt = at) else it }
    }

    override suspend fun resetDeferredCounts() {
        state.value = state.value.map { it.copy(deferredSinceUnlock = pending.remove(it.kind) ?: 0) }
    }
}

/**
 * ADR-044: the reminder pass against a real vault (fake Keystore) in every state a cold
 * WorkManager process can find it in, with the shared once-per-process nudge latch,
 * the hashed hint refresh and the reminded-set idempotence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainReminderRunnerTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /** 3 Oct 2026 20:35 IST — 20 h before the fixture train's 16:35 departure on the 4th. */
    private val now = Instant.parse("2026-10-03T15:05:00Z")
    private val keyFile = InMemoryKeyFileStore()
    private val keystore = FakeBackgroundKeyWrapper()
    private val syncStore = InMemorySyncStateStore()
    private val trains = FakeTrainRepository()
    private val settings = FakeSettingsRepository()
    private val posted = mutableListOf<TrainReminderContent>()
    private var nudgeLatched = false
    private var nudgeCalls = 0

    private val ticket =
        Fixtures.trainTicket(
            pnr = "8524167890",
            trainNumber = "12951",
            trainName = "Mumbai Rajdhani",
            journeyDate = java.time.LocalDate.of(2026, 10, 4),
            fromStation = "Mumbai Central",
        )
    private val stops =
        listOf(
            Fixtures.trainRouteStop(
                ticketId = ticket.id,
                stationName = "Mumbai Central (MMCT)",
                arrival = "",
                departure = "16:35",
                sortOrder = 0,
            ),
            Fixtures.trainRouteStop(
                ticketId = ticket.id,
                stationName = "New Delhi (NDLS)",
                arrival = "08:35",
                departure = "",
                day = 2,
                sortOrder = 1,
            ),
        )
    private val departure = Instant.parse("2026-10-04T11:05:00Z")

    private fun vault() =
        DefaultKeyVault(keyFile, iterations = 1_000, ioDispatcher = UnconfinedTestDispatcher(), backgroundKeyWrapper = keystore)

    private fun runner(
        vault: DefaultKeyVault,
        at: Instant = now,
    ) = TrainReminderRunner(
        gate = BackgroundSyncGate(vault, syncStore, Clock.fixed(at, ZoneOffset.UTC)),
        stateStore = syncStore,
        trainRepository = trains,
        settingsRepository = settings,
        post = { posted += it },
        notifyUnlockToSync = {
            nudgeCalls++
            if (nudgeLatched) false else true.also { nudgeLatched = true }
        },
        nudgeAlreadyPosted = { nudgeLatched },
        fallbackLabel = { "PNR $it" },
        now = { at },
        zone = zone,
    )

    private suspend fun setUpVault(backgroundKey: Boolean) {
        vault().also {
            it.setUp("pw".toCharArray())
            if (backgroundKey) it.enableBackgroundUnlock("pw".toCharArray())
        }
    }

    @Test
    fun locked_noOptIn_dueHint_nudgesOnceThroughTheSharedLatch_thenStaysQuiet() =
        runTest {
            setUpVault(backgroundKey = false)
            syncStore.trainHints = listOf(TrainDepartureHint(departure, PnrHash.of(ticket.pnr)))
            val cold = vault()

            val first = runner(cold).run()
            assertThat(first).isEqualTo(TrainReminderOutcome.Deferred(nudged = true, backgroundKeyEnabled = false))
            assertThat(TrainReminderWorker.resolveVerdict(first)).isEqualTo(ListenableWorker.Result.success())

            val second = runner(cold).run() // same process: latched (by us or by the flight poller)
            assertThat(second).isEqualTo(TrainReminderOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))
            assertThat(nudgeCalls).isEqualTo(1)
            assertThat(cold.isUnlocked).isFalse()
            assertThat(posted).isEmpty()
            assertThat(syncStore.pending[SyncWorkKind.TRAIN_REMINDER]).isEqualTo(2)
        }

    @Test
    fun locked_noOptIn_nothingDue_defersSilently() =
        runTest {
            setUpVault(backgroundKey = false)
            val hash = PnrHash.of(ticket.pnr)
            // Already reminded for this lead → not due; a far-out ticket → not due; OFF → never.
            syncStore.trainHints =
                listOf(TrainDepartureHint(departure, hash), TrainDepartureHint(departure.plus(Duration.ofDays(20)), "other"))
            syncStore.reminded += TrainReminderPolicy.key(hash, TrainReminderLead.ONE_DAY)
            assertThat(runner(vault()).run()).isEqualTo(TrainReminderOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))

            syncStore.reminded.clear()
            settings.setTrainReminderLead(TrainReminderLead.OFF)
            assertThat(runner(vault()).run()).isEqualTo(TrainReminderOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))

            settings.setTrainReminderLead(TrainReminderLead.ONE_DAY)
            syncStore.trainHints = emptyList() // fresh install, never unlocked
            assertThat(runner(vault()).run()).isEqualTo(TrainReminderOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))
            assertThat(nudgeCalls).isEqualTo(0)
        }

    @Test
    fun locked_flightPollerAlreadyNudgedThisProcess_trainStaysQuiet() =
        runTest {
            setUpVault(backgroundKey = false)
            syncStore.trainHints = listOf(TrainDepartureHint(departure, PnrHash.of(ticket.pnr)))
            nudgeLatched = true // AppLockNotifier.postedThisProcess — set by the flight poller

            assertThat(runner(vault()).run()).isEqualTo(TrainReminderOutcome.Deferred(nudged = false, backgroundKeyEnabled = false))
            assertThat(nudgeCalls).isEqualTo(0)
        }

    @Test
    fun locked_withOptIn_selfUnlocks_postsAndMarks_refreshesHashedHints() =
        runTest {
            setUpVault(backgroundKey = true)
            trains.seed(
                ticket,
                stops = stops,
                ticketPassengers = listOf(Fixtures.trainPassenger(ticketId = ticket.id, currentStatus = "WL 12")),
            )
            val cold = vault()

            val outcome = runner(cold).run() as TrainReminderOutcome.Ran
            assertThat(outcome.viaBackgroundKey).isTrue()
            assertThat(outcome.posted).isEqualTo(1)
            assertThat(cold.isUnlocked).isTrue()
            assertThat(nudgeCalls).isEqualTo(0)
            val content = posted.single()
            assertThat(content.trainLabel).isEqualTo("12951 Mumbai Rajdhani")
            assertThat(content.statusSummary).isEqualTo("WL 12")
            assertThat(content.daysUntilDeparture).isEqualTo(1)
            // The next LOCKED run knows exactly this: departure + hash, never the PNR.
            assertThat(syncStore.trainHints).containsExactly(TrainDepartureHint(departure, PnrHash.of(ticket.pnr)))
            assertThat(syncStore.trainHints.single().pnrHash).doesNotContain(ticket.pnr)
            assertThat(syncStore.reminded).containsExactly(TrainReminderPolicy.key(PnrHash.of(ticket.pnr), TrainReminderLead.ONE_DAY))
            assertThat(TrainReminderWorker.resolveVerdict(outcome)).isEqualTo(ListenableWorker.Result.success())
        }

    @Test
    fun locked_withOptIn_butKeyUnusable_defersWithRetry_andNeverNudges() =
        runTest {
            setUpVault(backgroundKey = true)
            keystore.deleteKey()
            syncStore.trainHints = listOf(TrainDepartureHint(departure, PnrHash.of(ticket.pnr)))

            val outcome = runner(vault()).run()
            assertThat(outcome).isEqualTo(TrainReminderOutcome.Deferred(nudged = false, backgroundKeyEnabled = true))
            assertThat(nudgeCalls).isEqualTo(0)
            assertThat(TrainReminderWorker.resolveVerdict(outcome)).isEqualTo(ListenableWorker.Result.retry())
        }

    @Test
    fun unlocked_postsOnce_secondRunIsIdempotent_leadChangeReArms_offPostsNothing() =
        runTest {
            val vault = vault().also { it.setUp("pw".toCharArray()) }
            trains.seed(ticket, stops = stops)
            // A second ticket far in the future is a candidate but not due.
            val later = Fixtures.trainTicket(pnr = "1234509876", trainNumber = "12009", journeyDate = java.time.LocalDate.of(2026, 10, 30))
            trains.seed(later)

            val first = runner(vault).run() as TrainReminderOutcome.Ran
            assertThat(first.posted).isEqualTo(1)
            assertThat(first.viaBackgroundKey).isFalse()
            assertThat(posted.single().pnr).isEqualTo(ticket.pnr)
            assertThat(syncStore.trainHints).hasSize(2)

            val second = runner(vault).run() as TrainReminderOutcome.Ran
            assertThat(second.posted).isEqualTo(0) // reminded set

            // Three hours later, still ahead of departure: still nothing new at the same lead.
            assertThat((runner(vault, at = now.plus(Duration.ofHours(3))).run() as TrainReminderOutcome.Ran).posted).isEqualTo(0)

            // The user moves the lead to 12 h: at 10 h before departure the new lead is due once more.
            settings.setTrainReminderLead(TrainReminderLead.TWELVE_HOURS)
            val tenHoursBefore = departure.minus(Duration.ofHours(10))
            assertThat((runner(vault, at = tenHoursBefore).run() as TrainReminderOutcome.Ran).posted).isEqualTo(1)
            assertThat(posted).hasSize(2)
            assertThat(posted.last().daysUntilDeparture).isEqualTo(0) // "Train today"

            // OFF: hints still refreshed, nothing posted.
            settings.setTrainReminderLead(TrainReminderLead.OFF)
            syncStore.reminded.clear()
            val off = runner(vault).run() as TrainReminderOutcome.Ran
            assertThat(off.posted).isEqualTo(0)
            assertThat(off.lead).isEqualTo(TrainReminderLead.OFF)
            assertThat(syncStore.trainHints).hasSize(2)
            assertThat(nudgeCalls).isEqualTo(0)
        }

    @Test
    fun unlocked_archivedPastAndDatelessTickets_neverRemind_andPrunesStaleRemindedKeys() =
        runTest {
            val vault = vault().also { it.setUp("pw".toCharArray()) }
            trains.seed(ticket.copy(archived = true), stops = stops)
            trains.seed(Fixtures.trainTicket(pnr = "1111122222", journeyDate = java.time.LocalDate.of(2026, 10, 2))) // departed yesterday
            trains.seed(Fixtures.trainTicket(pnr = "3333344444", journeyDate = null))
            // A ticket added INSIDE the window: 24 h lead, journey day with no route → start of 4 Oct IST = 20:35 → 3.5 h ahead.
            val insideWindow =
                Fixtures.trainTicket(
                    pnr = "5555566666",
                    trainNumber = "12009",
                    journeyDate = java.time.LocalDate.of(2026, 10, 4),
                )
            trains.seed(insideWindow)
            syncStore.reminded += TrainReminderPolicy.key(PnrHash.of("9999900000"), TrainReminderLead.ONE_DAY) // a deleted ticket's key

            val outcome = runner(vault).run() as TrainReminderOutcome.Ran
            assertThat(outcome.posted).isEqualTo(1)
            assertThat(posted.single().pnr).isEqualTo("5555566666")
            assertThat(posted.single().departureText).doesNotContain(":") // day only — route unknown
            // Hints: the departed one is still an active ticket (archive is the app-open task's job) but
            // not due; the archived and dateless ones are absent.
            assertThat(syncStore.trainHints.map { it.pnrHash }).containsExactly(PnrHash.of("1111122222"), PnrHash.of("5555566666"))
            assertThat(syncStore.reminded).containsExactly(TrainReminderPolicy.key(PnrHash.of("5555566666"), TrainReminderLead.ONE_DAY))
        }
}
