package com.itsluminous.cleartravel.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/** ADR-043: the plaintext timestamps + flight hint over a real (temp-file) Preferences DataStore. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSyncStateStoreTest {
    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + Job())
    private lateinit var store: DataStoreBackgroundSyncStateStore

    @Before
    fun setUp() {
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "sync-test.preferences_pb") })
        store = DataStoreBackgroundSyncStateStore(dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaults_everyKindPresent_nothingRecorded() =
        runTest {
            val statuses = store.statuses.first()
            assertThat(statuses.map { it.kind }).containsExactlyElementsIn(SyncWorkKind.entries).inOrder()
            assertThat(statuses).containsExactlyElementsIn(SyncWorkKind.entries.map { SyncWorkStatus(it) })
            assertThat(store.flightDepartureHints()).isEmpty()
        }

    @Test
    fun flightHints_roundTrip_dedupe_andClear() =
        runTest {
            val a = Instant.parse("2026-10-05T08:30:00Z")
            val b = Instant.parse("2026-10-12T20:15:00Z")
            store.setFlightDepartureHints(listOf(b, a, a))
            assertThat(store.flightDepartureHints()).containsExactly(a, b)
            store.setFlightDepartureHints(emptyList())
            assertThat(store.flightDepartureHints()).isEmpty()
        }

    @Test
    fun deferred_stampsAndCounts_perKind_completedStampsOnly_resetClearsCountsNotStamps() =
        runTest {
            val t1 = Instant.parse("2026-10-02T10:00:00Z")
            val t2 = Instant.parse("2026-10-02T11:00:00Z")
            val t3 = Instant.parse("2026-10-02T12:00:00Z")
            store.recordDeferred(SyncWorkKind.FLIGHT_POLL, t1)
            store.recordDeferred(SyncWorkKind.FLIGHT_POLL, t2)
            store.recordDeferred(SyncWorkKind.CALENDAR_SYNC, t1)
            store.recordCompleted(SyncWorkKind.SCHEDULED_BACKUP, t3)

            val byKind = store.statuses.first().associateBy { it.kind }
            assertThat(byKind[SyncWorkKind.FLIGHT_POLL]).isEqualTo(
                SyncWorkStatus(SyncWorkKind.FLIGHT_POLL, lastCompletedAt = null, lastDeferredAt = t2, deferredSinceUnlock = 2),
            )
            assertThat(byKind[SyncWorkKind.CALENDAR_SYNC]!!.deferredSinceUnlock).isEqualTo(1)
            assertThat(byKind[SyncWorkKind.SCHEDULED_BACKUP]).isEqualTo(
                SyncWorkStatus(SyncWorkKind.SCHEDULED_BACKUP, lastCompletedAt = t3),
            )
            assertThat(byKind[SyncWorkKind.DRIVE_UPLOAD]).isEqualTo(SyncWorkStatus(SyncWorkKind.DRIVE_UPLOAD))

            store.resetDeferredCounts()
            val afterUnlock = store.statuses.first().associateBy { it.kind }
            assertThat(afterUnlock[SyncWorkKind.FLIGHT_POLL]!!.deferredSinceUnlock).isEqualTo(0)
            assertThat(afterUnlock[SyncWorkKind.FLIGHT_POLL]!!.lastDeferredAt).isEqualTo(t2) // history stays
            assertThat(afterUnlock[SyncWorkKind.CALENDAR_SYNC]!!.deferredSinceUnlock).isEqualTo(0)
        }
}
