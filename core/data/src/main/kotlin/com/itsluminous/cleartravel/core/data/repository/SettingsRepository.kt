package com.itsluminous.cleartravel.core.data.repository

import com.itsluminous.cleartravel.core.model.BackupSchedule
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import kotlinx.coroutines.flow.Flow

/**
 * App settings. Non-secret preferences (theme, provider selection) live in a
 * Preferences DataStore; user-entered API keys live in EncryptedSharedPreferences
 * (ADR-007) and are exposed via suspend accessors only — never as Flows, so they
 * can't leak into logs/state dumps by accident.
 */
interface SettingsRepository {
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /** Selected train status provider id; null = default (WebView scrape). */
    val trainProviderId: Flow<String?>

    suspend fun setTrainProviderId(providerId: String?)

    /** Selected flight status provider id; null = default (WebView scrape). */
    val flightProviderId: Flow<String?>

    suspend fun setFlightProviderId(providerId: String?)

    /** User-entered train status API key (encrypted at rest, ADR-007); null = unset. */
    suspend fun trainApiKey(): String?

    suspend fun setTrainApiKey(key: String?)

    /** User-entered flight status API key (encrypted at rest, ADR-007); null = unset. */
    suspend fun flightApiKey(): String?

    suspend fun setFlightApiKey(key: String?)

    /** ADR-031: how long the app may stay in the background before the UI re-locks. */
    val lockTiming: Flow<LockTiming>

    suspend fun setLockTiming(timing: LockTiming)

    /**
     * ADR-032: true from the moment the first-run password is created until the
     * onboarding wizard finishes (Google step, restore-or-start-fresh). Defaults to
     * false so installs that predate the wizard never see it; a process death
     * mid-wizard resumes at the step after password creation.
     */
    val onboardingPending: Flow<Boolean>

    suspend fun setOnboardingPending(pending: Boolean)

    /** ADR-037: cadence of the automatic backup (local always, Drive when enabled); [BackupSchedule.OFF] by default. */
    val backupSchedule: Flow<BackupSchedule>

    suspend fun setBackupSchedule(schedule: BackupSchedule)
}
