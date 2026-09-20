package com.itsluminous.cleartravel.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import java.time.Instant

/** Room row for [Checklist] (ADR-004). `trip_id` null = standalone checklist. */
@Entity(
    tableName = "checklists",
    indices = [Index("trip_id")],
)
data class ChecklistEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "trip_id") val tripId: String?,
    val name: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [ChecklistItem] (ADR-004). */
@Entity(
    tableName = "checklist_items",
    indices = [Index("checklist_id")],
)
data class ChecklistItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "checklist_id") val checklistId: String,
    val text: String,
    val checked: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [ChecklistPreset] (ADR-004/ADR-006). */
@Entity(tableName = "checklist_presets")
data class ChecklistPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "built_in") val builtIn: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

/** Room row for [ChecklistPresetItem] (ADR-004/ADR-006). */
@Entity(
    tableName = "checklist_preset_items",
    indices = [Index("preset_id")],
)
data class ChecklistPresetItemEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "preset_id") val presetId: String,
    val text: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Instant,
    @ColumnInfo(name = "deleted_at") val deletedAt: Instant?,
)

fun Checklist.toEntity(): ChecklistEntity = ChecklistEntity(id, tripId, name, updatedAt, deletedAt)

fun ChecklistEntity.toModel(): Checklist = Checklist(id, tripId, name, updatedAt, deletedAt)

fun ChecklistItem.toEntity(): ChecklistItemEntity = ChecklistItemEntity(id, checklistId, text, checked, sortOrder, updatedAt, deletedAt)

fun ChecklistItemEntity.toModel(): ChecklistItem = ChecklistItem(id, checklistId, text, checked, sortOrder, updatedAt, deletedAt)

fun ChecklistPreset.toEntity(): ChecklistPresetEntity = ChecklistPresetEntity(id, name, builtIn, updatedAt, deletedAt)

fun ChecklistPresetEntity.toModel(): ChecklistPreset = ChecklistPreset(id, name, builtIn, updatedAt, deletedAt)

fun ChecklistPresetItem.toEntity(): ChecklistPresetItemEntity =
    ChecklistPresetItemEntity(id, presetId, text, sortOrder, updatedAt, deletedAt)

fun ChecklistPresetItemEntity.toModel(): ChecklistPresetItem = ChecklistPresetItem(id, presetId, text, sortOrder, updatedAt, deletedAt)
