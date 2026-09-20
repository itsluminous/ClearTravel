package com.itsluminous.cleartravel.core.model

import java.time.Instant

/**
 * A checklist. Usually attached to a trip via [tripId] but standalone checklists
 * ([tripId] = null) are allowed. Items live in [ChecklistItem] rows ordered by
 * `sortOrder`.
 */
data class Checklist(
    override val id: String = EntityIds.newId(),
    /** Owning trip; null = standalone checklist. */
    val tripId: String? = null,
    val name: String,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/** One line of a [Checklist]. */
data class ChecklistItem(
    override val id: String = EntityIds.newId(),
    val checklistId: String,
    val text: String,
    val checked: Boolean = false,
    val sortOrder: Int = 0,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/**
 * A reusable checklist template. Built-in presets ([builtIn] = true) are seeded from
 * a versioned asset on first run (ADR-003/ADR-006); user presets are created in
 * Settings → Manage presets. Appending a preset to a checklist COPIES its items —
 * editing a preset later never mutates existing checklists (ADR-006).
 */
data class ChecklistPreset(
    override val id: String = EntityIds.newId(),
    val name: String,
    val builtIn: Boolean = false,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity

/** One template line of a [ChecklistPreset]. */
data class ChecklistPresetItem(
    override val id: String = EntityIds.newId(),
    val presetId: String,
    val text: String,
    val sortOrder: Int = 0,
    override val updatedAt: Instant = Instant.now(),
    override val deletedAt: Instant? = null,
) : SyncableEntity
