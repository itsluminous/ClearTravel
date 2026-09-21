package com.itsluminous.cleartravel.feature.checklist

import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ChecklistRepository] mirroring the contract's observable semantics —
 * including ADR-006 appendPreset (copy, cumulative, dedupe-by-exact-text).
 */
class FakeChecklistRepository : ChecklistRepository {
    private val checklists = MutableStateFlow<List<Checklist>>(emptyList())
    private val items = MutableStateFlow<List<ChecklistItem>>(emptyList())

    /** Preset id → template texts, consumed by [appendPreset]. */
    val presetItems = mutableMapOf<String, List<String>>()

    /** Recorded (checklistId, presetId) pairs, newest last. */
    val appendPresetCalls = mutableListOf<Pair<String, String>>()

    /** Every item write batch ([saveItem] records a singleton batch), newest last. */
    val saveItemsCalls = mutableListOf<List<ChecklistItem>>()

    fun seedChecklist(checklist: Checklist) {
        checklists.value = checklists.value + checklist
    }

    fun seedItems(seeded: List<ChecklistItem>) {
        items.value = items.value + seeded
    }

    fun currentItems(checklistId: String): List<ChecklistItem> =
        items.value.filter { it.checklistId == checklistId }.sortedBy { it.sortOrder }

    override fun observeChecklists(): Flow<List<Checklist>> = checklists

    override fun observeChecklistsForTrip(tripId: String): Flow<List<Checklist>> =
        checklists.map { list ->
            list.filter {
                it.tripId ==
                    tripId
            }
        }

    override fun observeChecklist(id: String): Flow<Checklist?> = checklists.map { list -> list.firstOrNull { it.id == id } }

    override fun observeItems(checklistId: String): Flow<List<ChecklistItem>> =
        items.map { list -> list.filter { it.checklistId == checklistId }.sortedBy { it.sortOrder } }

    override suspend fun save(checklist: Checklist): Checklist {
        checklists.value = checklists.value.filterNot { it.id == checklist.id } + checklist
        return checklist
    }

    override suspend fun saveItem(item: ChecklistItem): ChecklistItem = saveItems(listOf(item)).single()

    override suspend fun saveItems(items: List<ChecklistItem>): List<ChecklistItem> {
        saveItemsCalls += items
        val ids = items.map { it.id }.toSet()
        this.items.value = this.items.value.filterNot { it.id in ids } + items
        return items
    }

    override suspend fun setItemChecked(
        itemId: String,
        checked: Boolean,
    ) {
        items.value = items.value.map { if (it.id == itemId) it.copy(checked = checked) else it }
    }

    override suspend fun appendPreset(
        checklistId: String,
        presetId: String,
    ): List<ChecklistItem> {
        appendPresetCalls += checklistId to presetId
        val existingTexts = currentItems(checklistId).map { it.text }.toSet()
        var nextOrder = (currentItems(checklistId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added =
            presetItems[presetId]
                .orEmpty()
                .filterNot { it in existingTexts }
                .map { text -> ChecklistItem(checklistId = checklistId, text = text, sortOrder = nextOrder++) }
        items.value = items.value + added
        return added
    }

    override suspend fun deleteChecklist(id: String) {
        checklists.value = checklists.value.filterNot { it.id == id }
        items.value = items.value.filterNot { it.checklistId == id }
    }

    override suspend fun deleteItem(id: String) {
        items.value = items.value.filterNot { it.id == id }
    }
}

/** In-memory [ChecklistPresetRepository] — only what the checklist ViewModels need. */
class FakeChecklistPresetRepository : ChecklistPresetRepository {
    private val presets = MutableStateFlow<List<ChecklistPreset>>(emptyList())
    private val items = MutableStateFlow<List<ChecklistPresetItem>>(emptyList())

    fun seedPreset(preset: ChecklistPreset) {
        presets.value = presets.value + preset
    }

    override fun observePresets(): Flow<List<ChecklistPreset>> = presets

    override suspend fun getPreset(id: String): ChecklistPreset? = presets.value.firstOrNull { it.id == id }

    override fun observeItems(presetId: String): Flow<List<ChecklistPresetItem>> =
        items.map { list -> list.filter { it.presetId == presetId }.sortedBy { it.sortOrder } }

    override suspend fun getItems(presetId: String): List<ChecklistPresetItem> =
        items.value.filter { it.presetId == presetId }.sortedBy { it.sortOrder }

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
