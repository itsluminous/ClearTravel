package com.itsluminous.cleartravel.feature.checklist

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ChecklistDetailViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val checklistRepository = FakeChecklistRepository()
    private val presetRepository = FakeChecklistPresetRepository()
    private val checklist = Checklist(name = "Trek packing")

    init {
        checklistRepository.seedChecklist(checklist)
    }

    private fun viewModel() =
        ChecklistDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(CHECKLIST_ID_ARG to checklist.id)),
            checklistRepository = checklistRepository,
            presetRepository = presetRepository,
        )

    private fun seedItems(vararg texts: String) {
        checklistRepository.seedItems(
            texts.mapIndexed { index, text ->
                ChecklistItem(checklistId = checklist.id, text = text, sortOrder = index)
            },
        )
    }

    @Test
    fun `items emits checklist items in sort order`() =
        runTest {
            seedItems("Tent", "Boots", "Headlamp")
            viewModel().items.test {
                assertThat(awaitItem().map { it.text }).containsExactly("Tent", "Boots", "Headlamp").inOrder()
            }
        }

    @Test
    fun `setItemChecked toggles the item`() =
        runTest {
            seedItems("Tent")
            val viewModel = viewModel()
            val itemId =
                viewModel.items.value
                    .first()
                    .id

            viewModel.setItemChecked(itemId, checked = true)

            assertThat(checklistRepository.currentItems(checklist.id).first().checked).isTrue()
        }

    @Test
    fun `addItem appends with the next sort order`() =
        runTest {
            seedItems("Tent", "Boots")
            val viewModel = viewModel()

            viewModel.addItem("  Headlamp  ")

            val items = checklistRepository.currentItems(checklist.id)
            assertThat(items.map { it.text }).containsExactly("Tent", "Boots", "Headlamp").inOrder()
            assertThat(items.last().sortOrder).isEqualTo(2)
            assertThat(items.last().checked).isFalse()
        }

    @Test
    fun `addItem ignores blank text`() =
        runTest {
            val viewModel = viewModel()
            viewModel.addItem("   ")
            assertThat(checklistRepository.currentItems(checklist.id)).isEmpty()
        }

    @Test
    fun `removeItem deletes the item`() =
        runTest {
            seedItems("Tent", "Boots")
            val viewModel = viewModel()
            val bootsId =
                viewModel.items.value
                    .first { it.text == "Boots" }
                    .id

            viewModel.removeItem(bootsId)

            assertThat(checklistRepository.currentItems(checklist.id).map { it.text }).containsExactly("Tent")
        }

    @Test
    fun `moveItem down re-inserts the item after the rows it passed`() =
        runTest {
            seedItems("Tent", "Boots", "Headlamp", "Stove")
            val viewModel = viewModel()

            viewModel.moveItem(from = 0, to = 2)

            val items = checklistRepository.currentItems(checklist.id)
            assertThat(items.map { it.text }).containsExactly("Boots", "Headlamp", "Tent", "Stove").inOrder()
            // Sort keys are the original slots — the set of orders is unchanged.
            assertThat(items.map { it.sortOrder }).containsExactly(0, 1, 2, 3).inOrder()
        }

    @Test
    fun `moveItem up re-inserts the item before the rows it passed`() =
        runTest {
            seedItems("Tent", "Boots", "Headlamp", "Stove")
            val viewModel = viewModel()

            viewModel.moveItem(from = 3, to = 1)

            assertThat(checklistRepository.currentItems(checklist.id).map { it.text })
                .containsExactly("Tent", "Stove", "Boots", "Headlamp")
                .inOrder()
        }

    @Test
    fun `moveItem only rewrites the affected range`() =
        runTest {
            seedItems("Tent", "Boots", "Headlamp", "Stove")
            val viewModel = viewModel()
            checklistRepository.saveItemsCalls.clear()

            viewModel.moveItem(from = 1, to = 2)

            assertThat(checklistRepository.saveItemsCalls).hasSize(1)
            assertThat(checklistRepository.saveItemsCalls.single().map { it.text })
                .containsExactly("Headlamp", "Boots")
                .inOrder()
        }

    @Test
    fun `moveItem with the same index or out of range is a no-op`() =
        runTest {
            seedItems("Tent", "Boots")
            val viewModel = viewModel()
            checklistRepository.saveItemsCalls.clear()

            viewModel.moveItem(from = 1, to = 1)
            viewModel.moveItem(from = 0, to = 2)
            viewModel.moveItem(from = -1, to = 0)

            assertThat(checklistRepository.saveItemsCalls).isEmpty()
            assertThat(checklistRepository.currentItems(checklist.id).map { it.text })
                .containsExactly("Tent", "Boots")
                .inOrder()
        }

    @Test
    fun `renameItem saves the trimmed text and keeps order and checked state`() =
        runTest {
            seedItems("Tent", "Boots")
            val viewModel = viewModel()
            val bootsId =
                viewModel.items.value
                    .first { it.text == "Boots" }
                    .id
            viewModel.setItemChecked(bootsId, checked = true)

            viewModel.renameItem(bootsId, "  Hiking boots ")

            val items = checklistRepository.currentItems(checklist.id)
            assertThat(items.map { it.text }).containsExactly("Tent", "Hiking boots").inOrder()
            assertThat(items.last().checked).isTrue()
        }

    @Test
    fun `renameItem ignores blank or unchanged text`() =
        runTest {
            seedItems("Tent")
            val viewModel = viewModel()
            val tentId =
                viewModel.items.value
                    .first()
                    .id
            checklistRepository.saveItemsCalls.clear()

            viewModel.renameItem(tentId, "   ")
            viewModel.renameItem(tentId, "Tent")
            viewModel.renameItem("missing", "Tarp")

            assertThat(checklistRepository.saveItemsCalls).isEmpty()
        }

    @Test
    fun `appendPreset calls the repository and emits the added count`() =
        runTest {
            seedItems("Passport")
            checklistRepository.presetItems["preset-int"] = listOf("Passport", "Visa", "Adapter")
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.appendPreset("preset-int")
                val event = awaitItem() as ChecklistDetailEvent.PresetAppended
                // "Passport" already exists — dedupe means fewer than the preset size.
                assertThat(event.addedCount).isEqualTo(2)
            }
            assertThat(checklistRepository.appendPresetCalls).containsExactly(checklist.id to "preset-int")
        }

    @Test
    fun `appendPreset is cumulative across multiple presets`() =
        runTest {
            checklistRepository.presetItems["preset-int"] = listOf("Passport", "Visa")
            checklistRepository.presetItems["preset-meds"] = listOf("Paracetamol", "Band-aids")
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.appendPreset("preset-int")
                assertThat((awaitItem() as ChecklistDetailEvent.PresetAppended).addedCount).isEqualTo(2)
                viewModel.appendPreset("preset-meds")
                assertThat((awaitItem() as ChecklistDetailEvent.PresetAppended).addedCount).isEqualTo(2)
            }
            assertThat(checklistRepository.currentItems(checklist.id).map { it.text })
                .containsExactly("Passport", "Visa", "Paracetamol", "Band-aids")
                .inOrder()
        }

    @Test
    fun `re-appending the same preset adds nothing`() =
        runTest {
            checklistRepository.presetItems["preset-int"] = listOf("Passport", "Visa")
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.appendPreset("preset-int")
                assertThat((awaitItem() as ChecklistDetailEvent.PresetAppended).addedCount).isEqualTo(2)
                viewModel.appendPreset("preset-int")
                assertThat((awaitItem() as ChecklistDetailEvent.PresetAppended).addedCount).isEqualTo(0)
            }
        }

    @Test
    fun `deleteChecklist deletes and emits Deleted`() =
        runTest {
            seedItems("Tent")
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.deleteChecklist()
                assertThat(awaitItem()).isEqualTo(ChecklistDetailEvent.Deleted)
            }
            assertThat(checklistRepository.currentItems(checklist.id)).isEmpty()
        }
}
