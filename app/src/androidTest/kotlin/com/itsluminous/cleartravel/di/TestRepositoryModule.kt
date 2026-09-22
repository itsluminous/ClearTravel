package com.itsluminous.cleartravel.di

import com.itsluminous.cleartravel.core.data.backup.BackupManager
import com.itsluminous.cleartravel.core.data.backup.DefaultBackupManager
import com.itsluminous.cleartravel.core.data.di.RepositoryModule
import com.itsluminous.cleartravel.core.data.preset.AssetBuiltInPresetSource
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetSource
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TravelDocumentRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineAttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineFlightRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineTrainRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineTravelDocumentRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineTripRepository
import com.itsluminous.cleartravel.core.model.BackupSchedule
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the production [RepositoryModule] bindings except [SettingsRepository], which
 * is swapped for the in-memory [FakeSettingsRepository]. Rationale in
 * [TestSettingsModule]: the real `DefaultSettingsRepository` drags in a Preferences
 * DataStore that cannot be safely re-created once per test-class Hilt component.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [RepositoryModule::class])
abstract class TestRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindTripRepository(impl: OfflineTripRepository): TripRepository

    @Binds
    @Singleton
    abstract fun bindItineraryRepository(impl: OfflineItineraryRepository): ItineraryRepository

    @Binds
    @Singleton
    abstract fun bindChecklistRepository(impl: OfflineChecklistRepository): ChecklistRepository

    @Binds
    @Singleton
    abstract fun bindChecklistPresetRepository(impl: OfflineChecklistPresetRepository): ChecklistPresetRepository

    @Binds
    @Singleton
    abstract fun bindTrainRepository(impl: OfflineTrainRepository): TrainRepository

    @Binds
    @Singleton
    abstract fun bindFlightRepository(impl: OfflineFlightRepository): FlightRepository

    @Binds
    @Singleton
    abstract fun bindAttachmentRepository(impl: OfflineAttachmentRepository): AttachmentRepository

    @Binds
    @Singleton
    abstract fun bindTravelDocumentRepository(impl: OfflineTravelDocumentRepository): TravelDocumentRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: FakeSettingsRepository): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindBuiltInPresetSource(impl: AssetBuiltInPresetSource): BuiltInPresetSource

    @Binds
    @Singleton
    abstract fun bindBackupManager(impl: DefaultBackupManager): BackupManager
}

/** In-memory [SettingsRepository]: pure Kotlin flows, no disk, safe across test classes. */
@Singleton
class FakeSettingsRepository
    @Inject
    constructor() : SettingsRepository {
        private val theme = MutableStateFlow(ThemeMode.SYSTEM)
        private val trainProvider = MutableStateFlow<String?>(null)
        private val flightProvider = MutableStateFlow<String?>(null)
        private var trainKey: String? = null
        private var flightKey: String? = null

        override val themeMode: Flow<ThemeMode> = theme

        override suspend fun setThemeMode(mode: ThemeMode) {
            theme.value = mode
        }

        override val trainProviderId: Flow<String?> = trainProvider

        override suspend fun setTrainProviderId(providerId: String?) {
            trainProvider.value = providerId
        }

        override val flightProviderId: Flow<String?> = flightProvider

        override suspend fun setFlightProviderId(providerId: String?) {
            flightProvider.value = providerId
        }

        override suspend fun trainApiKey(): String? = trainKey

        override suspend fun setTrainApiKey(key: String?) {
            trainKey = key
        }

        override suspend fun flightApiKey(): String? = flightKey

        override suspend fun setFlightApiKey(key: String?) {
            flightKey = key
        }

        private val timing = MutableStateFlow(LockTiming.DEFAULT)
        override val lockTiming: Flow<LockTiming> = timing

        override suspend fun setLockTiming(timing: LockTiming) {
            this.timing.value = timing
        }

        /** ADR-032: false by default so every pre-existing e2e skips the onboarding wizard. */
        private val onboarding = MutableStateFlow(false)
        override val onboardingPending: Flow<Boolean> = onboarding

        override suspend fun setOnboardingPending(pending: Boolean) {
            onboarding.value = pending
        }

        private val schedule = MutableStateFlow(BackupSchedule.DEFAULT)
        override val backupSchedule: Flow<BackupSchedule> = schedule

        override suspend fun setBackupSchedule(schedule: BackupSchedule) {
            this.schedule.value = schedule
        }
    }
