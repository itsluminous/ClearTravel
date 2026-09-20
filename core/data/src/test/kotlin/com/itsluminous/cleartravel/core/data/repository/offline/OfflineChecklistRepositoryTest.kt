package com.itsluminous.cleartravel.core.data.repository.offline

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetDefinition
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetSource
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

/** In-memory [BuiltInPresetSource] so preset repo tests don't touch assets. */
private class FakePresetSource(
    var definitions: List<BuiltInPresetDefinition> = emptyList(),
) : BuiltInPresetSource {
    override fun load(): List<BuiltInPresetDefinition> = definitions
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineChecklistRepositoryTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var checklists: OfflineChecklistRepository
    private lateinit var presets: OfflineChecklistPresetRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(3600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        checklists = OfflineChecklistRepository(db.checklistDao(), db.checklistPresetDao(), clock)
        presets = OfflineChecklistPresetRepository(db.checklistPresetDao(), FakePresetSource(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun presetWith(
        name: String,
        items: List<String>,
    ): String {
        val preset = presets.save(Fixtures.checklistPreset(name = name))
        presets.saveItems(items.mapIndexed { i, text -> Fixtures.checklistPresetItem(presetId = preset.id, text = text, sortOrder = i) })
        return preset.id
    }

    @Test
    fun `save bumps updatedAt`() =
        runTest {
            val saved = checklists.save(Fixtures.checklist(updatedAt = Fixtures.NOW))
            assertThat(saved.updatedAt).isEqualTo(clock.instant())
        }

    @Test
    fun `appendPreset copies preset items to the end in order`() =
        runTest {
            val checklist = checklists.save(Fixtures.checklist())
            checklists.saveItem(Fixtures.checklistItem(checklistId = checklist.id, text = "Existing", sortOrder = 0))
            val presetId = presetWith("International travel", listOf("Passport", "Visa documents"))

            val appended = checklists.appendPreset(checklist.id, presetId)

            assertThat(appended.map { it.text }).containsExactly("Passport", "Visa documents").inOrder()
            val texts = checklists.observeItems(checklist.id).first().map { it.text }
            assertThat(texts).containsExactly("Existing", "Passport", "Visa documents").inOrder()
        }

    @Test
    fun `multiple presets append cumulatively to one checklist`() =
        runTest {
            // The user story from ADR-006: International travel + Medicines on one checklist.
            val checklist = checklists.save(Fixtures.checklist())
            val international = presetWith("International travel", listOf("Passport", "Power bank"))
            val medicines = presetWith("Medicines", listOf("Paracetamol", "Band-aids"))

            checklists.appendPreset(checklist.id, international)
            checklists.appendPreset(checklist.id, medicines)

            val texts = checklists.observeItems(checklist.id).first().map { it.text }
            assertThat(texts).containsExactly("Passport", "Power bank", "Paracetamol", "Band-aids").inOrder()
        }

    @Test
    fun `appendPreset skips items whose exact text already exists`() =
        runTest {
            val checklist = checklists.save(Fixtures.checklist())
            val first = presetWith("First", listOf("Passport", "Power bank"))
            val second = presetWith("Second", listOf("Power bank", "Sunscreen"))

            checklists.appendPreset(checklist.id, first)
            val appended = checklists.appendPreset(checklist.id, second)

            assertThat(appended.map { it.text }).containsExactly("Sunscreen")
            val texts = checklists.observeItems(checklist.id).first().map { it.text }
            assertThat(texts).containsExactly("Passport", "Power bank", "Sunscreen").inOrder()
        }

    @Test
    fun `appending the same preset twice is a no-op the second time`() =
        runTest {
            val checklist = checklists.save(Fixtures.checklist())
            val presetId = presetWith("Trek", listOf("Backpack", "Water bottle"))

            checklists.appendPreset(checklist.id, presetId)
            val secondRun = checklists.appendPreset(checklist.id, presetId)

            assertThat(secondRun).isEmpty()
            assertThat(checklists.observeItems(checklist.id).first()).hasSize(2)
        }

    @Test
    fun `appendPreset never mutates the preset`() =
        runTest {
            val checklist = checklists.save(Fixtures.checklist())
            val presetId = presetWith("Trek", listOf("Backpack"))
            val before = presets.getItems(presetId)

            checklists.appendPreset(checklist.id, presetId)

            assertThat(presets.getItems(presetId)).isEqualTo(before)
        }

    @Test
    fun `editing a preset later never mutates checklists built from it`() =
        runTest {
            val checklist = checklists.save(Fixtures.checklist())
            val presetId = presetWith("Trek", listOf("Backpack", "Water bottle"))
            checklists.appendPreset(checklist.id, presetId)

            // Rewrite the preset completely: rename an item and delete another.
            val items = presets.getItems(presetId)
            presets.saveItems(listOf(items[0].copy(text = "Bigger backpack")))
            presets.deleteItem(items[1].id)

            val texts = checklists.observeItems(checklist.id).first().map { it.text }
            assertThat(texts).containsExactly("Backpack", "Water bottle").inOrder()
        }

    @Test
    fun `setItemChecked flips the flag and bumps updatedAt`() =
        runTest {
            val item = checklists.saveItem(Fixtures.checklistItem(checklistId = "list", checked = false))

            checklists.setItemChecked(item.id, checked = true)

            val stored = checklists.observeItems("list").first().single()
            assertThat(stored.checked).isTrue()
            assertThat(stored.updatedAt).isEqualTo(clock.instant())
        }

    @Test
    fun `deleteChecklist cascades to its items`() =
        runTest {
            val checklist = checklists.save(Fixtures.checklist())
            checklists.saveItem(Fixtures.checklistItem(checklistId = checklist.id))

            checklists.deleteChecklist(checklist.id)

            assertThat(checklists.observeChecklist(checklist.id).first()).isNull()
            assertThat(checklists.observeItems(checklist.id).first()).isEmpty()
        }
}
