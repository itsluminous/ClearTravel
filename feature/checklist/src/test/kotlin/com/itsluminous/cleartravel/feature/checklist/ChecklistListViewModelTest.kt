package com.itsluminous.cleartravel.feature.checklist

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.model.Checklist
import com.itsluminous.cleartravel.core.model.ChecklistItem
import com.itsluminous.cleartravel.core.model.ChecklistPreset
import com.itsluminous.cleartravel.core.testing.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ChecklistListViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val checklistRepository = FakeChecklistRepository()
    private val presetRepository = FakeChecklistPresetRepository()

    private fun viewModel() = ChecklistListViewModel(checklistRepository, presetRepository)

    @Test
    fun `checklists emits rows with packing progress`() =
        runTest {
            val checklist = Checklist(name = "Goa trip")
            checklistRepository.seedChecklist(checklist)
            checklistRepository.seedItems(
                listOf(
                    ChecklistItem(checklistId = checklist.id, text = "Passport", checked = true, sortOrder = 0),
                    ChecklistItem(checklistId = checklist.id, text = "Charger", checked = false, sortOrder = 1),
                    ChecklistItem(checklistId = checklist.id, text = "Sunscreen", checked = true, sortOrder = 2),
                ),
            )

            viewModel().checklists.test {
                val rows = awaitItemNotNull()
                assertThat(rows).hasSize(1)
                assertThat(rows.first().checklist.name).isEqualTo("Goa trip")
                assertThat(rows.first().doneCount).isEqualTo(2)
                assertThat(rows.first().totalCount).isEqualTo(3)
            }
        }

    @Test
    fun `checklists emits empty list when there are none`() =
        runTest {
            viewModel().checklists.test {
                assertThat(awaitItemNotNull()).isEmpty()
            }
        }

    @Test
    fun `checklists progress updates when an item is toggled`() =
        runTest {
            val checklist = Checklist(name = "Trek")
            val item = ChecklistItem(checklistId = checklist.id, text = "Boots", checked = false)
            checklistRepository.seedChecklist(checklist)
            checklistRepository.seedItems(listOf(item))

            viewModel().checklists.test {
                assertThat(awaitItemNotNull().first().doneCount).isEqualTo(0)
                checklistRepository.setItemChecked(item.id, checked = true)
                assertThat(awaitItemNotNull().first().doneCount).isEqualTo(1)
            }
        }

    @Test
    fun `createChecklist saves and emits Created event`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.createChecklist("  Beach bag  ", presetId = null)
                val event = awaitItem() as ChecklistListEvent.Created
                val rows = viewModel.checklists.value.orEmpty()
                assertThat(rows.map { it.checklist.id }).contains(event.checklistId)
                assertThat(rows.first { it.checklist.id == event.checklistId }.checklist.name)
                    .isEqualTo("Beach bag")
            }
        }

    @Test
    fun `createChecklist from preset copies the preset items`() =
        runTest {
            val preset = ChecklistPreset(name = "International travel", builtIn = true)
            presetRepository.seedPreset(preset)
            checklistRepository.presetItems[preset.id] = listOf("Passport", "Visa", "Adapter")

            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.createChecklist("Japan", presetId = preset.id)
                val event = awaitItem() as ChecklistListEvent.Created
                assertThat(checklistRepository.appendPresetCalls).containsExactly(event.checklistId to preset.id)
                assertThat(checklistRepository.currentItems(event.checklistId).map { it.text })
                    .containsExactly("Passport", "Visa", "Adapter")
                    .inOrder()
            }
        }

    @Test
    fun `createChecklist ignores a blank name`() =
        runTest {
            val viewModel = viewModel()
            viewModel.events.test {
                viewModel.createChecklist("   ", presetId = null)
                expectNoEvents()
            }
            assertThat(viewModel.checklists.value).isEmpty()
        }
}

/** Skips the initial null loading emission of `StateFlow<List<T>?>`. */
private suspend fun <T> app.cash.turbine.TurbineTestContext<List<T>?>.awaitItemNotNull(): List<T> {
    var value = awaitItem()
    while (value == null) {
        value = awaitItem()
    }
    return value
}
