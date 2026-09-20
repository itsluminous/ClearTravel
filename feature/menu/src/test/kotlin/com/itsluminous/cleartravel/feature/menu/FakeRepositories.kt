package com.itsluminous.cleartravel.feature.menu

import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.SettingsRepository
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

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
}

/** In-memory [ChecklistPresetRepository] for menu ViewModel tests. */
class FakePresetRepository : ChecklistPresetRepository {
    private val presets = MutableStateFlow<List<ChecklistPreset>>(emptyList())
    private val items = MutableStateFlow<List<ChecklistPresetItem>>(emptyList())

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
