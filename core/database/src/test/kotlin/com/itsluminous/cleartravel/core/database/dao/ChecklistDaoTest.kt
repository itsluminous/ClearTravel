package com.itsluminous.cleartravel.core.database.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.database.entity.toEntity
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import com.itsluminous.cleartravel.core.testing.syncColumns
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecklistDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: ChecklistDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.checklistDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `standalone and trip-scoped checklists round-trip`() =
        runTest {
            val standalone = Fixtures.checklist(tripId = null, name = "Standalone")
            val tripScoped = Fixtures.checklist(tripId = "trip-1", name = "Trip scoped")
            dao.upsert(standalone.toEntity())
            dao.upsert(tripScoped.toEntity())

            assertThat(dao.observeAll().first()).hasSize(2)
            assertThat(dao.observeForTrip("trip-1").first()).containsExactly(tripScoped.toEntity())
            assertThat(dao.getForTrip("trip-1")).containsExactly(tripScoped.toEntity())
        }

    @Test
    fun `items round-trip ordered by sort order`() =
        runTest {
            val listId = Fixtures.FIXED_ID
            val second = Fixtures.checklistItem(checklistId = listId, text = "Second", sortOrder = 1)
            val first = Fixtures.checklistItem(checklistId = listId, text = "First", sortOrder = 0)
            dao.upsertItems(listOf(second, first).map { it.toEntity() })

            assertThat(dao.getItems(listId).map { it.text }).containsExactly("First", "Second").inOrder()
            assertThat(dao.observeItems(listId).first()).hasSize(2)
        }

    @Test
    fun `soft delete of checklist hides it and bumps updated_at`() =
        runTest {
            val checklist = Fixtures.checklist(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW)
            dao.upsert(checklist.toEntity())
            val deleteAt = Fixtures.NOW.plusSeconds(30)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            val columns = db.syncColumns("checklists", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }

    @Test
    fun `softDeleteItemsFor tombstones all items of the checklist and bumps updated_at`() =
        runTest {
            val listId = Fixtures.FIXED_ID
            val item = Fixtures.checklistItem(checklistId = listId, updatedAt = Fixtures.NOW)
            val otherListItem = Fixtures.checklistItem(checklistId = "other")
            dao.upsertItems(listOf(item, otherListItem).map { it.toEntity() })
            val deleteAt = Fixtures.NOW.plusSeconds(30)

            dao.softDeleteItemsFor(listId, deleteAt)

            assertThat(dao.getItems(listId)).isEmpty()
            assertThat(dao.getItems("other")).hasSize(1)
            val columns = db.syncColumns("checklist_items", item.id)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChecklistPresetDaoTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var dao: ChecklistPresetDao

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        dao = db.checklistPresetDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `presets order built-ins before user presets`() =
        runTest {
            val user = Fixtures.checklistPreset(name = "AAA user preset", builtIn = false)
            val builtIn = Fixtures.checklistPreset(name = "ZZZ built-in", builtIn = true)
            dao.upsert(user.toEntity())
            dao.upsert(builtIn.toEntity())

            assertThat(dao.observeAll().first().map { it.name })
                .containsExactly("ZZZ built-in", "AAA user preset")
                .inOrder()
        }

    @Test
    fun `getByIdIncludingDeleted still sees a tombstoned preset`() =
        runTest {
            val preset = Fixtures.checklistPreset(id = Fixtures.FIXED_ID)
            dao.upsert(preset.toEntity())
            dao.softDelete(Fixtures.FIXED_ID, Fixtures.NOW.plusSeconds(5))

            assertThat(dao.getById(Fixtures.FIXED_ID)).isNull()
            assertThat(dao.getByIdIncludingDeleted(Fixtures.FIXED_ID)).isNotNull()
        }

    @Test
    fun `soft delete bumps updated_at`() =
        runTest {
            val preset = Fixtures.checklistPreset(id = Fixtures.FIXED_ID, updatedAt = Fixtures.NOW)
            dao.upsert(preset.toEntity())
            val deleteAt = Fixtures.NOW.plusSeconds(30)

            dao.softDelete(Fixtures.FIXED_ID, deleteAt)

            val columns = db.syncColumns("checklist_presets", Fixtures.FIXED_ID)
            assertThat(columns?.deletedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
            assertThat(columns?.updatedAtEpochMillis).isEqualTo(deleteAt.toEpochMilli())
        }

    @Test
    fun `preset items round-trip ordered and softDeleteItemsFor clears them`() =
        runTest {
            val presetId = Fixtures.FIXED_ID
            val b = Fixtures.checklistPresetItem(presetId = presetId, text = "B", sortOrder = 1)
            val a = Fixtures.checklistPresetItem(presetId = presetId, text = "A", sortOrder = 0)
            dao.upsertItems(listOf(b, a).map { it.toEntity() })

            assertThat(dao.getItems(presetId).map { it.text }).containsExactly("A", "B").inOrder()

            dao.softDeleteItemsFor(presetId, Fixtures.NOW.plusSeconds(1))
            assertThat(dao.getItems(presetId)).isEmpty()
            assertThat(dao.observeItems(presetId).first()).isEmpty()
        }
}
