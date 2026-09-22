package com.itsluminous.cleartravel.feature.applock

import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.model.BackupSchedule
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsRepository]; only the onboarding flag matters to this module. */
class FakeSettingsRepository : SettingsRepository {
    val onboarding = MutableStateFlow(false)
    val onboardingWrites = mutableListOf<Boolean>()

    private val theme = MutableStateFlow(ThemeMode.SYSTEM)
    private val trainProvider = MutableStateFlow<String?>(null)
    private val flightProvider = MutableStateFlow<String?>(null)
    private val timing = MutableStateFlow(LockTiming.DEFAULT)

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

    override suspend fun trainApiKey(): String? = null

    override suspend fun setTrainApiKey(key: String?) = Unit

    override suspend fun flightApiKey(): String? = null

    override suspend fun setFlightApiKey(key: String?) = Unit

    override val lockTiming: Flow<LockTiming> = timing

    override suspend fun setLockTiming(timing: LockTiming) {
        this.timing.value = timing
    }

    override val onboardingPending: Flow<Boolean> = onboarding

    override suspend fun setOnboardingPending(pending: Boolean) {
        onboardingWrites += pending
        onboarding.value = pending
    }

    private val schedule = MutableStateFlow(BackupSchedule.DEFAULT)
    override val backupSchedule: Flow<BackupSchedule> = schedule

    override suspend fun setBackupSchedule(schedule: BackupSchedule) {
        this.schedule.value = schedule
    }
}
