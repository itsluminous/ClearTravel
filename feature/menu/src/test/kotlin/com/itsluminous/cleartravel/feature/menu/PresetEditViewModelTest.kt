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
    fun `rename is refused for a built-in preset`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            val viewModel = viewModel(builtIn.id)

            viewModel.rename("Hacked")

            assertThat(presetRepository.getPreset(builtIn.id)?.name).isEqualTo("Trek")
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
    fun `addItem is refused for a built-in preset`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            val viewModel = viewModel(builtIn.id)

            viewModel.addItem("Rope")

            assertThat(presetRepository.currentItems(builtIn.id)).isEmpty()
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
    fun `moveItem swaps neighbouring sort orders`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat", "Towel")
            val viewModel = viewModel(preset.id)
            val hatId =
                viewModel.items.value
                    .first { it.text == "Hat" }
                    .id

            viewModel.moveItem(hatId, up = true)

            assertThat(presetRepository.currentItems(preset.id).map { it.text })
                .containsExactly("Hat", "Sunscreen", "Towel")
                .inOrder()
        }

    @Test
    fun `moveItem at the boundary is a no-op`() =
        runTest {
            val preset = seedUserPreset("Sunscreen", "Hat")
            val viewModel = viewModel(preset.id)
            val hatId =
                viewModel.items.value
                    .first { it.text == "Hat" }
                    .id

            viewModel.moveItem(hatId, up = false)

            assertThat(presetRepository.currentItems(preset.id).map { it.text })
                .containsExactly("Sunscreen", "Hat")
                .inOrder()
        }
}
