package com.itsluminous.cleartravel.feature.menu

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PresetEditViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val presetRepository = FakePresetRepository()

    private fun viewModel(presetId: String) =
        PresetEditViewModel(
            savedStateHandle = SavedStateHandle(mapOf(PRESET_ID_ARG to presetId)),
            presetRepository = presetRepository,
        )

    private fun seedUserPreset(vararg itemTexts: String): ChecklistPreset {
        val preset = ChecklistPreset(name = "My preset")
        presetRepository.seedPreset(preset)
        presetRepository.seedItems(
            itemTexts.mapIndexed { index, text ->
                ChecklistPresetItem(presetId = preset.id, text = text, sortOrder = index)
            },
        )
        return preset
    }

    @Test
    fun `preset and items emit`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat")
            val viewModel = viewModel(preset.id)

            viewModel.items.test {
                assertThat(awaitItem().map { it.text }).containsExactly("Sunscreen", "Hat").inOrder()
            }
            assertThat(viewModel.preset.value?.name).isEqualTo("My preset")
        }

    @Test
    fun `rename saves the trimmed name`() =
        runTest {
            val preset = seedUserPreset()
            val viewModel = viewModel(preset.id)

            viewModel.rename("  Beach kit  ")

            assertThat(presetRepository.getPreset(preset.id)?.name).isEqualTo("Beach kit")
        }

    @Test
    fun `rename edits a built-in preset like any other (ADR-021)`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            val viewModel = viewModel(builtIn.id)

            viewModel.rename("My trek")

            val saved = presetRepository.getPreset(builtIn.id)
            assertThat(saved?.name).isEqualTo("My trek")
            // Still flagged built-in — the seeder keys on the fixed id, not the name.
            assertThat(saved?.builtIn).isTrue()
        }

    @Test
    fun `rename ignores blank or unchanged names`() =
        runTest {
            val preset = seedUserPreset()
            val viewModel = viewModel(preset.id)

            viewModel.rename("   ")
            viewModel.rename("My preset")

            assertThat(presetRepository.getPreset(preset.id)?.name).isEqualTo("My preset")
        }

    @Test
    fun `addItem appends with the next sort order`() =
        runTest {
            val preset = seedUserPreset("Sunscreen")
            val viewModel = viewModel(preset.id)

            viewModel.addItem("  Hat  ")

            val items = presetRepository.currentItems(preset.id)
            assertThat(items.map { it.text }).containsExactly("Sunscreen", "Hat").inOrder()
            assertThat(items.last().sortOrder).isEqualTo(1)
        }

    @Test
    fun `addItem works on a built-in preset (ADR-021)`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            val viewModel = viewModel(builtIn.id)

            viewModel.addItem("Rope")

            assertThat(presetRepository.currentItems(builtIn.id).map { it.text }).containsExactly("Rope")
        }

    @Test
    fun `removeItem deletes the item`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat")
            val viewModel = viewModel(preset.id)
            val hatId =
                viewModel.items.value
                    .first { it.text == "Hat" }
                    .id

            viewModel.removeItem(hatId)

            assertThat(presetRepository.currentItems(preset.id).map { it.text }).containsExactly("Sunscreen")
        }

    @Test
    fun `removeItem works on a built-in preset (ADR-021)`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            presetRepository.seedItems(listOf(ChecklistPresetItem(presetId = builtIn.id, text = "Rope", sortOrder = 0)))
            val viewModel = viewModel(builtIn.id)

            viewModel.removeItem(
                viewModel.items.value
                    .single()
                    .id,
            )

            assertThat(presetRepository.currentItems(builtIn.id)).isEmpty()
        }

    @Test
    fun `renameItem saves the trimmed text in place`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat")
            val viewModel = viewModel(preset.id)
            val hatId =
                viewModel.items.value
                    .first { it.text == "Hat" }
                    .id

            viewModel.renameItem(hatId, "  Sun hat ")

            assertThat(presetRepository.currentItems(preset.id).map { it.text })
                .containsExactly("Sunscreen", "Sun hat")
                .inOrder()
        }

    @Test
    fun `renameItem ignores blank or unchanged text`() =
        runTest {
            val preset = seedUserPreset("Sunscreen")
            val viewModel = viewModel(preset.id)
            val id =
                viewModel.items.value
                    .single()
                    .id
            presetRepository.saveItemsCalls.clear()

            viewModel.renameItem(id, " ")
            viewModel.renameItem(id, "Sunscreen")
            viewModel.renameItem("missing", "Towel")

            assertThat(presetRepository.saveItemsCalls).isEmpty()
        }

    @Test
    fun `moveItem down re-inserts after the rows it passed`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat", "Towel", "Flip-flops")
            val viewModel = viewModel(preset.id)

            viewModel.moveItem(from = 0, to = 2)

            val items = presetRepository.currentItems(preset.id)
            assertThat(items.map { it.text }).containsExactly("Hat", "Towel", "Sunscreen", "Flip-flops").inOrder()
            assertThat(items.map { it.sortOrder }).containsExactly(0, 1, 2, 3).inOrder()
        }

    @Test
    fun `moveItem up re-inserts before the rows it passed and rewrites only the range`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat", "Towel", "Flip-flops")
            val viewModel = viewModel(preset.id)
            presetRepository.saveItemsCalls.clear()

            viewModel.moveItem(from = 2, to = 0)

            assertThat(presetRepository.currentItems(preset.id).map { it.text })
                .containsExactly("Towel", "Sunscreen", "Hat", "Flip-flops")
                .inOrder()
            assertThat(presetRepository.saveItemsCalls.single().map { it.text })
                .containsExactly("Towel", "Sunscreen", "Hat")
                .inOrder()
        }

    @Test
    fun `moveItem on a built-in preset persists (ADR-021)`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            presetRepository.seedItems(
                listOf("Rope", "Boots").mapIndexed { index, text ->
                    ChecklistPresetItem(presetId = builtIn.id, text = text, sortOrder = index)
                },
            )
            val viewModel = viewModel(builtIn.id)

            viewModel.moveItem(from = 1, to = 0)

            assertThat(presetRepository.currentItems(builtIn.id).map { it.text }).containsExactly("Boots", "Rope").inOrder()
        }

    @Test
    fun `moveItem with the same index or out of range is a no-op`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat")
            val viewModel = viewModel(preset.id)
            presetRepository.saveItemsCalls.clear()

            viewModel.moveItem(from = 1, to = 1)
            viewModel.moveItem(from = 1, to = 2)
            viewModel.moveItem(from = -1, to = 0)

            assertThat(presetRepository.saveItemsCalls).isEmpty()
            assertThat(presetRepository.currentItems(preset.id).map { it.text })
                .containsExactly("Sunscreen", "Hat")
                .inOrder()
        }
}
