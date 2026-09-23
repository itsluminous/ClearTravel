package com.itsluminous.cleartravel.core.data.repository.offline

import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetSource
import com.itsluminous.cleartravel.core.data.repository.ChecklistPresetRepository
import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.database.dao.ChecklistDao
import com.itsluminous.cleartravel.core.database.dao.ChecklistPresetDao
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.database.entity.toModel
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.model.EntityIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed [ChecklistRepository]. Every write bumps `updatedAt` (ADR-002). */
@Singleton
class OfflineChecklistRepository
    @Inject
    constructor(
        private val checklistDao: ChecklistDao,
        private val presetDao: ChecklistPresetDao,
        private val clock: Clock,
    ) : ChecklistRepository {
        override fun observeChecklists(): Flow<List<Checklist>> = checklistDao.observeAll().map { rows -> rows.map { it.toModel() } }

        override fun observeChecklistsForTrip(tripId: String): Flow<List<Checklist>> =
            checklistDao.observeForTrip(tripId).map { rows -> rows.map { it.toModel() } }

        override fun observeChecklist(id: String): Flow<Checklist?> = checklistDao.observeById(id).map { it?.toModel() }

        override fun observeItems(checklistId: String): Flow<List<ChecklistItem>> =
            checklistDao.observeItems(checklistId).map { rows -> rows.map { it.toModel() } }

        override suspend fun save(checklist: Checklist): Checklist {
            val stamped = checklist.copy(updatedAt = clock.instant())
            checklistDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun saveItem(item: ChecklistItem): ChecklistItem = saveItems(listOf(item)).first()

        override suspend fun saveItems(items: List<ChecklistItem>): List<ChecklistItem> {
            val now = clock.instant()
            val stamped = items.map { it.copy(updatedAt = now) }
            checklistDao.upsertItems(stamped.map { it.toEntity() })
            return stamped
        }

        override suspend fun setItemChecked(
            itemId: String,
            checked: Boolean,
        ) {
            val current = checklistDao.getItemById(itemId) ?: return
            checklistDao.upsertItems(listOf(current.copy(checked = checked, updatedAt = clock.instant())))
        }

        /**
         * ADR-006: appends a COPY (fresh row ids) of the preset's live items after the
         * checklist's current items, skipping items whose exact text already exists
         * live in the checklist. Cumulative across multiple presets; never touches
         * the preset.
         */
        override suspend fun appendPreset(
            checklistId: String,
            presetId: String,
        ): List<ChecklistItem> {
            val existing = checklistDao.getItems(checklistId)
            val existingTexts = existing.map { it.text }.toSet()
            val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            val now = clock.instant()
            val appended =
                presetDao
                    .getItems(presetId)
                    .filter { it.text !in existingTexts }
                    .mapIndexed { index, presetItem ->
                        ChecklistItem(
                            id = EntityIds.newId(),
                            checklistId = checklistId,
                            text = presetItem.text,
                            checked = false,
                            sortOrder = nextOrder + index,
                            updatedAt = now,
                        )
                    }
            if (appended.isNotEmpty()) {
                checklistDao.upsertItems(appended.map { it.toEntity() })
            }
            return appended
        }

        override suspend fun deleteChecklist(id: String) {
            val now = clock.instant()
            checklistDao.softDeleteItemsFor(id, now)
            checklistDao.softDelete(id, now)
        }

        override suspend fun deleteItem(id: String) {
            checklistDao.softDeleteItem(id, clock.instant())
        }
    }

/** Room-backed [ChecklistPresetRepository]. Every write bumps `updatedAt` (ADR-002). */
@Singleton
class OfflineChecklistPresetRepository
    @Inject
    constructor(
        private val presetDao: ChecklistPresetDao,
        private val builtInPresetSource: BuiltInPresetSource,
        private val clock: Clock,
    ) : ChecklistPresetRepository {
        override fun observePresets(): Flow<List<ChecklistPreset>> = presetDao.observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun getPreset(id: String): ChecklistPreset? = presetDao.getById(id)?.toModel()

        override fun observeItems(presetId: String): Flow<List<ChecklistPresetItem>> =
            presetDao.observeItems(presetId).map { rows -> rows.map { it.toModel() } }

        override suspend fun getItems(presetId: String): List<ChecklistPresetItem> = presetDao.getItems(presetId).map { it.toModel() }

        override suspend fun save(preset: ChecklistPreset): ChecklistPreset {
            val stamped = preset.copy(updatedAt = clock.instant())
            presetDao.upsert(stamped.toEntity())
            return stamped
        }

        override suspend fun saveItems(items: List<ChecklistPresetItem>): List<ChecklistPresetItem> {
            val now = clock.instant()
            val stamped = items.map { it.copy(updatedAt = now) }
            presetDao.upsertItems(stamped.map { it.toEntity() })
            return stamped
        }

        override suspend fun duplicatePreset(
            id: String,
            newName: String,
        ): ChecklistPreset? {
            val source = presetDao.getById(id) ?: return null
            val now = clock.instant()
            val copy = ChecklistPreset(id = EntityIds.newId(), name = newName, builtIn = false, updatedAt = now)
            presetDao.upsert(copy.toEntity())
            val items =
                presetDao.getItems(source.id).map { item ->
                    ChecklistPresetItem(
                        id = EntityIds.newId(),
                        presetId = copy.id,
                        text = item.text,
                        sortOrder = item.sortOrder,
                        updatedAt = now,
                    )
                }
            if (items.isNotEmpty()) {
                presetDao.upsertItems(items.map { it.toEntity() })
            }
            return copy
        }

        override suspend fun deletePreset(id: String) {
            val now = clock.instant()
            presetDao.softDeleteItemsFor(id, now)
            presetDao.softDelete(id, now)
        }

        override suspend fun deleteItem(id: String) {
            presetDao.softDeleteItem(id, clock.instant())
        }

        override suspend fun seedBuiltInPresets() {
            val now = clock.instant()
            builtInPresetSource.load().forEach { definition ->
                if (presetDao.getByIdIncludingDeleted(definition.id) != null) return@forEach
                presetDao.upsert(ChecklistPreset(id = definition.id, name = definition.name, builtIn = true, updatedAt = now).toEntity())
                val items =
                    definition.items.mapIndexed { index, text ->
                        ChecklistPresetItem(
                            id = seededItemId(definition.id, index),
                            presetId = definition.id,
                            text = text,
                            sortOrder = index,
                            updatedAt = now,
                        )
                    }
                presetDao.upsertItems(items.map { it.toEntity() })
            }
        }

        companion object {
            /**
             * ADR-040: the id of the [index]-th seeded item of built-in preset [presetId]
             * is DERIVED, not random, so every install seeds the same row ids and a
             * backup merged into a freshly seeded install matches them by id instead of
             * inserting a second copy of every built-in item.
             */
            fun seededItemId(
                presetId: String,
                index: Int,
            ): String = UUID.nameUUIDFromBytes("cleartravel:preset-item:$presetId:$index".toByteArray(Charsets.UTF_8)).toString()
        }
    }
