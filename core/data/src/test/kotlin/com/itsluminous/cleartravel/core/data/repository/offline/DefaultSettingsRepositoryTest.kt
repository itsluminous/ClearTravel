package com.itsluminous.cleartravel.core.data.repository.offline

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.security.lock.LockTiming
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Uses a plain SharedPreferences behind the [SecurePreferences] injection point —
 * Robolectric has no Android Keystore, and the encryption itself is androidx's to
 * test (ADR-007). What we own is the read/write/clear contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultSettingsRepositoryTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val dataStoreScope = CoroutineScope(testDispatcher + Job())
    private lateinit var repository: DefaultSettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dataStore: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(tmpFolder.root, "settings-test.preferences_pb") },
            )
        val plainPrefs = context.getSharedPreferences("test_secure", Context.MODE_PRIVATE)
        repository = DefaultSettingsRepository(dataStore, plainPrefs)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun `theme mode defaults to SYSTEM and round-trips`() =
        runTest(testDispatcher) {
            assertThat(repository.themeMode.first()).isEqualTo(ThemeMode.SYSTEM)

            repository.setThemeMode(ThemeMode.DARK)

            assertThat(repository.themeMode.first()).isEqualTo(ThemeMode.DARK)
        }

    @Test
    fun `provider ids default to null, round-trip, and clear`() =
        runTest(testDispatcher) {
            assertThat(repository.trainProviderId.first()).isNull()
            assertThat(repository.flightProviderId.first()).isNull()

            repository.setTrainProviderId("scrape")
            repository.setFlightProviderId("api")
            assertThat(repository.trainProviderId.first()).isEqualTo("scrape")
            assertThat(repository.flightProviderId.first()).isEqualTo("api")

            repository.setFlightProviderId(null)
            assertThat(repository.flightProviderId.first()).isNull()
        }

    @Test
    fun `api keys default to null, round-trip, and clear`() =
        runTest(testDispatcher) {
            assertThat(repository.flightApiKey()).isNull()
            assertThat(repository.trainApiKey()).isNull()

            repository.setFlightApiKey("flight-secret")
            repository.setTrainApiKey("train-secret")
            assertThat(repository.flightApiKey()).isEqualTo("flight-secret")
            assertThat(repository.trainApiKey()).isEqualTo("train-secret")

            repository.setFlightApiKey(null)
            assertThat(repository.flightApiKey()).isNull()
            assertThat(repository.trainApiKey()).isEqualTo("train-secret")
        }

    @Test
    fun `onboarding pending defaults to false so upgraded installs skip the wizard, and round-trips`() =
        runTest(testDispatcher) {
            assertThat(repository.onboardingPending.first()).isFalse()

            repository.setOnboardingPending(true)
            assertThat(repository.onboardingPending.first()).isTrue()

            repository.setOnboardingPending(false)
            assertThat(repository.onboardingPending.first()).isFalse()
        }

    @Test
    fun `lock timing defaults to one minute, and an explicit choice is kept over the default`() =
        runTest(testDispatcher) {
            // ADR-034: the fallback is 1 minute; it is never written, so a stored value wins.
            assertThat(repository.lockTiming.first()).isEqualTo(LockTiming.ONE_MINUTE)

            repository.setLockTiming(LockTiming.NEVER)
            assertThat(repository.lockTiming.first()).isEqualTo(LockTiming.NEVER)

            repository.setLockTiming(LockTiming.IMMEDIATELY)
            assertThat(repository.lockTiming.first()).isEqualTo(LockTiming.IMMEDIATELY)
        }
}
