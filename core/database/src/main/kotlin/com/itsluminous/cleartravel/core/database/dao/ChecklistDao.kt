package com.itsluminous.cleartravel.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.itsluminous.cleartravel.core.database.entity.ChecklistEntity
import com.itsluminous.cleartravel.core.database.entity.ChecklistItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Checklists + their items. All read queries exclude tombstoned rows (ADR-002). */
@Dao
interface ChecklistDao {
    @Query("SELECT * FROM checklists WHERE deleted_at IS NULL ORDER BY name")
    fun observeAll(): Flow<List<ChecklistEntity>>

    @Query("SELECT * FROM checklists WHERE trip_id = :tripId AND deleted_at IS NULL ORDER BY name")
    fun observeForTrip(tripId: String): Flow<List<ChecklistEntity>>

    @Query("SELECT * FROM checklists WHERE trip_id = :tripId AND deleted_at IS NULL ORDER BY name")
    suspend fun getForTrip(tripId: String): List<ChecklistEntity>

    @Query("SELECT * FROM checklists WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: String): Flow<ChecklistEntity?>

    @Query("SELECT * FROM checklists WHERE id = :id AND deleted_at IS NULL")
    suspend fun getById(id: String): ChecklistEntity?

    @Query("SELECT * FROM checklist_items WHERE checklist_id = :checklistId AND deleted_at IS NULL ORDER BY sort_order")
    fun observeItems(checklistId: String): Flow<List<ChecklistItemEntity>>

    @Query("SELECT * FROM checklist_items WHERE checklist_id = :checklistId AND deleted_at IS NULL ORDER BY sort_order")
    suspend fun getItems(checklistId: String): List<ChecklistItemEntity>

    @Query("SELECT * FROM checklist_items WHERE id = :id AND deleted_at IS NULL")
    suspend fun getItemById(id: String): ChecklistItemEntity?

    @Upsert
    suspend fun upsert(checklist: ChecklistEntity)

    @Upsert
    suspend fun upsertItems(items: List<ChecklistItemEntity>)

    /** Soft delete (ADR-002): sets the tombstone and bumps `updated_at` in one write. */
    @Query("UPDATE checklists SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(
        id: String,
        at: Instant,
    )

    @Query("UPDATE checklist_items SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDeleteItem(
        id: String,
        at: Instant,
    )

    /** Soft-deletes every live item of a checklist (used when the checklist is deleted). */
    @Query("UPDATE checklist_items SET deleted_at = :at, updated_at = :at WHERE checklist_id = :checklistId AND deleted_at IS NULL")
    suspend fun softDeleteItemsFor(
        checklistId: String,
        at: Instant,
    )
}
