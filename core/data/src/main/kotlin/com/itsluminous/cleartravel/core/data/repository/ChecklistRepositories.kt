package com.itsluminous.cleartravel.core.data.repository

import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import kotlinx.coroutines.flow.Flow

/** Checklists + items aggregate (ADR-004). Preset append semantics per ADR-006. */
interface ChecklistRepository {
    fun observeChecklists(): Flow<List<Checklist>>

    fun observeChecklistsForTrip(tripId: String): Flow<List<Checklist>>

    fun observeChecklist(id: String): Flow<Checklist?>

    /** Live items ordered by `sortOrder`. */
    fun observeItems(checklistId: String): Flow<List<ChecklistItem>>

    /** Upserts [checklist] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(checklist: Checklist): Checklist

    /** Upserts [item] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun saveItem(item: ChecklistItem): ChecklistItem

    /** Bulk upsert (reorder); all rows get the same bumped `updatedAt`. */
    suspend fun saveItems(items: List<ChecklistItem>): List<ChecklistItem>

    suspend fun setItemChecked(
        itemId: String,
        checked: Boolean,
    )

    /**
     * Appends a COPY of the preset's items to the END of the checklist (ADR-006).
     * Cumulative: call repeatedly with different presets to combine them (e.g.
     * "International travel" + "Medicines"). Items whose exact text already exists
     * live in the checklist are skipped (dedupe-by-text). The preset itself is never
     * mutated, and later preset edits never touch checklists built from it. Returns
     * the newly inserted items.
     */
    suspend fun appendPreset(
        checklistId: String,
        presetId: String,
    ): List<ChecklistItem>

    /** Soft-deletes the checklist AND its items. */
    suspend fun deleteChecklist(id: String)

    suspend fun deleteItem(id: String)
}

/** Checklist preset templates aggregate (ADR-004/ADR-006). */
interface ChecklistPresetRepository {
    /** Live presets, built-ins first then by name. */
    fun observePresets(): Flow<List<ChecklistPreset>>

    suspend fun getPreset(id: String): ChecklistPreset?

    fun observeItems(presetId: String): Flow<List<ChecklistPresetItem>>

    suspend fun getItems(presetId: String): List<ChecklistPresetItem>

    /** Upserts [preset] with a bumped `updatedAt`; returns the stored copy. */
    suspend fun save(preset: ChecklistPreset): ChecklistPreset

    /** Bulk upsert of template items; all rows get the same bumped `updatedAt`. */
    suspend fun saveItems(items: List<ChecklistPresetItem>): List<ChecklistPresetItem>

    /** Copies a preset (new ids, `builtIn` = false); returns the copy or null if absent. */
    suspend fun duplicatePreset(
        id: String,
        newName: String,
    ): ChecklistPreset?

    /** Soft-deletes the preset AND its template items. Existing checklists keep their items. */
    suspend fun deletePreset(id: String)

    suspend fun deleteItem(id: String)

    /**
     * Seeds the built-in presets from the versioned asset (ADR-003/ADR-006).
     * Idempotent: a preset id that already exists — INCLUDING tombstoned rows, so a
     * user-deleted built-in stays deleted — is never re-inserted or overwritten.
     */
    suspend fun seedBuiltInPresets()
}
