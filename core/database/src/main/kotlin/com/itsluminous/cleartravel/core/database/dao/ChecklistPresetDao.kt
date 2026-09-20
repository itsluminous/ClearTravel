package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.ChecklistPresetEntity
import com.itsluminous.cleartravel.core.database.entity.ChecklistPresetItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Checklist presets + template items. Read queries exclude tombstoned rows (ADR-002). */
@Dao
interface ChecklistPresetDao {
    @Query("SELECT * FROM checklist_presets WHERE deleted_at IS NULL ORDER BY built_in DESC, name")
    fun observeAll(): Flow<List<ChecklistPresetEntity>>

    @Query("SELECT * FROM checklist_presets WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): ChecklistPresetEntity?

    /**
     * Existence probe INCLUDING tombstoned rows — the seeder must not resurrect a
     * built-in preset the user has deleted (ADR-006).
     */
    @Query("SELECT * FROM checklist_presets WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): ChecklistPresetEntity?

    @Query("SELECT * FROM checklist_preset_items WHERE preset_id = :presetId AND deleted_at IS NULL ORDER BY sort_order")
    fun observeItems(presetId: String): Flow<List<ChecklistPresetItemEntity>>

    @Query("SELECT * FROM checklist_preset_items WHERE preset_id = :presetId AND deleted_at IS NULL ORDER BY sort_order")
    suspend fun getItems(presetId: String): List<ChecklistPresetItemEntity>

    @Upsert
    suspend fun upsert(preset: ChecklistPresetEntity)

    @Upsert
    suspend fun upsertItems(items: List<ChecklistPresetItemEntity>)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE checklist_presets SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )

    @Query("UPDATE checklist_preset_items SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDeleteItem(
        id: String,
        at: Instant,
    )

    /** Soft-deletes every live item of a preset (used when the preset is deleted). */
    @Query("UPDATE checklist_preset_items SET deleted_at = :at, updated_at = :at WHERE preset_id = :presetId AND deleted_at IS NULL")
    suspend fun softDeleteItemsFor(
        presetId: String,
        at: Instant,
    )
}
