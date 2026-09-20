package com.itsluminous.cleartravel.feature.menu

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class PresetManagerViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val presetRepository = FakePresetRepository()

    private fun viewModel() = PresetManagerViewModel(presetRepository)

    @Test
    fun `presets emits repository presets`() =
        runTest {
            presetRepository.seedPreset(ChecklistPreset(name = "Trek", builtIn = true))
            presetRepository.seedPreset(ChecklistPreset(name = "My preset"))

            viewModel().presets.test {
                var value = awaitItem()
                while (value == null) {
                    value = awaitItem()
                }
                assertThat(value.map { it.name }).containsExactly("Trek", "My preset")
            }
        }

    @Test
    fun `createPreset saves and emits Created`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.createPreset("  Beach kit  ")
                val event = awaitItem() as PresetManagerEvent.Created
                val saved = presetRepository.currentPresets().first { it.id == event.presetId }
                assertThat(saved.name).isEqualTo("Beach kit")
                assertThat(saved.builtIn).isFalse()
            }
        }

    @Test
    fun `createPreset ignores a blank name`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.createPreset("   ")
                expectNoEvents()
            }
            assertThat(presetRepository.currentPresets()).isEmpty()
        }

    @Test
    fun `duplicatePreset copies items and emits Duplicated`() =
        runTest {
            val builtIn = ChecklistPreset(name = "International travel", builtIn = true)
            presetRepository.seedPreset(builtIn)
            presetRepository.seedItems(
                listOf(
                    ChecklistPresetItem(presetId = builtIn.id, text = "Passport", sortOrder = 0),
                    ChecklistPresetItem(presetId = builtIn.id, text = "Visa", sortOrder = 1),
                ),
            )
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.duplicatePreset(builtIn.id, "International travel (copy)")
                assertThat(awaitItem()).isEqualTo(PresetManagerEvent.Duplicated)
            }

            val copy = presetRepository.currentPresets().first { it.name == "International travel (copy)" }
            assertThat(copy.builtIn).isFalse()
            assertThat(presetRepository.currentItems(copy.id).map { it.text })
                .containsExactly("Passport", "Visa")
                .inOrder()
        }

    @Test
    fun `duplicatePreset of a missing preset emits nothing`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.duplicatePreset("missing", "copy")
                expectNoEvents()
            }
        }

    @Test
    fun `deletePreset removes a user preset and emits Deleted`() =
        runTest {
            val preset = ChecklistPreset(name = "My preset")
            presetRepository.seedPreset(preset)
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.deletePreset(preset)
                assertThat(awaitItem()).isEqualTo(PresetManagerEvent.Deleted)
            }
            assertThat(presetRepository.currentPresets()).isEmpty()
        }

    @Test
    fun `deletePreset refuses a built-in preset`() =
        runTest {
            val builtIn = ChecklistPreset(name = "Trek", builtIn = true)
            presetRepository.seedPreset(builtIn)
            val viewModel = viewModel()

            viewModel.events.test {
                viewModel.deletePreset(builtIn)
                expectNoEvents()
            }
            assertThat(presetRepository.currentPresets()).hasSize(1)
        }
}
