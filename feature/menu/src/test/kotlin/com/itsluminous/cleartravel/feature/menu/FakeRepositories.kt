package com.itsluminous.cleartravel.feature.menu

import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.data.sync.BackgroundSyncStateStore
import com.itsluminous.cleartravel.core.data.sync.TrainDepartureHint
import com.itsluminous.cleartravel.core.data.sync.SyncWorkKind
import com.itsluminous.cleartravel.core.data.sync.SyncWorkStatus
import com.itsluminous.cleartravel.core.model.BackupSchedule
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.model.TrainReminderLead
import com.itsluminous.cleartravel.core.security.lock.LockTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** In-memory [SettingsRepository] for menu ViewModel tests. */
class FakeSettingsRepository : SettingsRepository {
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

    private val reminderLead = MutableStateFlow(TrainReminderLead.DEFAULT)
    override val trainReminderLead: Flow<TrainReminderLead> = reminderLead

    override suspend fun setTrainReminderLead(lead: TrainReminderLead) {
        reminderLead.value = lead
    }
}

/** In-memory [ChecklistPresetRepository] for menu ViewModel tests. */
class FakePresetRepository : ChecklistPresetRepository {
    private val presets = MutableStateFlow<List<ChecklistPreset>>(emptyList())
    private val items = MutableStateFlow<List<ChecklistPresetItem>>(emptyList())

    /** Every template-item write batch, newest last. */
    val saveItemsCalls = mutableListOf<List<ChecklistPresetItem>>()

    fun seedPreset(preset: ChecklistPreset) {
        presets.value = presets.value + preset
    }

    fun seedItems(seeded: List<ChecklistPresetItem>) {
        items.value = items.value + seeded
    }

    fun currentPresets(): List<ChecklistPreset> = presets.value

    fun currentItems(presetId: String): List<ChecklistPresetItem> = items.value.filter { it.presetId == presetId }.sortedBy { it.sortOrder }

    override fun observePresets(): Flow<List<ChecklistPreset>> = presets

    override suspend fun getPreset(id: String): ChecklistPreset? = presets.value.firstOrNull { it.id == id }

    override fun observeItems(presetId: String): Flow<List<ChecklistPresetItem>> =
        items.map { list -> list.filter { it.presetId == presetId }.sortedBy { it.sortOrder } }

    override suspend fun getItems(presetId: String): List<ChecklistPresetItem> = currentItems(presetId)

    override suspend fun save(preset: ChecklistPreset): ChecklistPreset {
        presets.value = presets.value.filterNot { it.id == preset.id } + preset
        return preset
    }

    override suspend fun saveItems(items: List<ChecklistPresetItem>): List<ChecklistPresetItem> {
        saveItemsCalls += items
        this.items.value = this.items.value.filterNot { existing -> items.any { it.id == existing.id } } + items
        return items
    }

    override suspend fun duplicatePreset(
        id: String,
        newName: String,
    ): ChecklistPreset? {
        val source = getPreset(id) ?: return null
        val copy = ChecklistPreset(name = newName, builtIn = false)
        presets.value = presets.value + copy
        val copiedItems =
            currentItems(source.id).map { item ->
                ChecklistPresetItem(presetId = copy.id, text = item.text, sortOrder = item.sortOrder)
            }
        items.value = items.value + copiedItems
        return copy
    }

    override suspend fun deletePreset(id: String) {
        presets.value = presets.value.filterNot { it.id == id }
        items.value = items.value.filterNot { it.presetId == id }
    }

    override suspend fun deleteItem(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }

    override suspend fun seedBuiltInPresets() = Unit
}

/** In-memory [BackgroundSyncStateStore] (ADR-043) for the security ViewModel tests. */
class FakeBackgroundSyncStateStore : BackgroundSyncStateStore {
    private var hints: List<Instant> = emptyList()
    val state = MutableStateFlow(SyncWorkKind.entries.map { SyncWorkStatus(it) })
    val pending = mutableMapOf<SyncWorkKind, Int>()

    override suspend fun flightDepartureHints(): List<Instant> = hints

    override suspend fun setFlightDepartureHints(departures: Collection<Instant>) {
        hints = departures.toList()
    }

    var trainHints: List<TrainDepartureHint> = emptyList()
    val reminded = mutableSetOf<String>()

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

    override val statuses: Flow<List<SyncWorkStatus>> = state

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
