package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.core.designsystem.R as DesignR
import com.itsluminous.cleartravel.feature.checklist.R as ChecklistR

/**
 * Checklist happy path (hermetic — in-memory Room seeded with the built-in presets):
 * create a checklist from the "Domestic trip" preset → its items appear; append the
 * "Medicines" preset → the "N items added" snackbar shows and the new items land.
 * Then the ADR-021 row controls: every row has a drag handle plus edit/delete icons
 * (no up/down arrows), and the pencil → edit dialog renames an item in place.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ChecklistE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun createFromPreset_thenAppendSecondPreset_showsSnackbar() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_checklist)).performClick()

        // FAB → create dialog; wait for the async preset seeding to surface.
        composeRule
            .onNodeWithContentDescription(composeRule.string(ChecklistR.string.checklist_add_fab))
            .performClick()
        composeRule
            .onNodeWithText(composeRule.string(ChecklistR.string.checklist_name_label))
            .performTextInput(CHECKLIST_NAME)
        composeRule.waitForText(FIRST_PRESET)
        composeRule.onNodeWithText(FIRST_PRESET).performClick()
        composeRule.onNodeWithText(composeRule.string(ChecklistR.string.checklist_create_confirm)).performClick()

        // Auto-navigates into the new checklist: preset items appear.
        composeRule.waitForText(FIRST_PRESET_ITEM)

        // Append a second preset (cumulative multi-append, ADR-006).
        composeRule
            .onNodeWithContentDescription(composeRule.string(ChecklistR.string.checklist_append_preset))
            .performClick()
        composeRule.waitForText(SECOND_PRESET)
        composeRule.onNodeWithText(SECOND_PRESET).performClick()

        // Snackbar reports how many items were copied over…
        composeRule.waitForText(SNACKBAR_FRAGMENT, substring = true)
        // …and the appended preset's items are on the list.
        composeRule.waitForText(SECOND_PRESET_ITEM)

        // ADR-021 rows: one drag handle + edit + delete per item, no move arrows.
        val handles =
            composeRule
                .onAllNodesWithContentDescription(composeRule.string(DesignR.string.designsystem_reorder_handle))
                .fetchSemanticsNodes()
                .size
        assertThat(handles).isAtLeast(2)
        composeRule
            .onAllNodesWithContentDescription(composeRule.string(ChecklistR.string.checklist_item_edit))
            .assertCountEquals(handles)
        composeRule
            .onAllNodesWithContentDescription(composeRule.string(ChecklistR.string.checklist_item_delete))
            .assertCountEquals(handles)
        composeRule.onAllNodesWithText(LEGACY_MOVE_UP).assertCountEquals(0)

        // Pencil on the "Power bank" row → dialog → rename → new text lands, old is gone.
        composeRule
            .onNode(
                hasContentDescription(composeRule.string(ChecklistR.string.checklist_item_edit)) and
                    hasParent(hasText(FIRST_PRESET_ITEM)),
            ).performClick()
        composeRule.waitForText(composeRule.string(ChecklistR.string.checklist_edit_item_title))
        composeRule.onNode(hasText(FIRST_PRESET_ITEM) and hasSetTextAction()).performTextReplacement(RENAMED_ITEM)
        composeRule.onNodeWithText(composeRule.string(ChecklistR.string.checklist_save)).performClick()
        composeRule.waitForText(RENAMED_ITEM)
        composeRule.onAllNodesWithText(FIRST_PRESET_ITEM).assertCountEquals(0)
    }

    private companion object {
        const val CHECKLIST_NAME = "Goa packing"

        // Built-in preset fixture data (core:data assets/presets/builtin-presets.json).
        const val FIRST_PRESET = "Domestic trip"
        const val FIRST_PRESET_ITEM = "Power bank"
        const val SECOND_PRESET = "Medicines"
        const val SECOND_PRESET_ITEM = "Paracetamol"
        const val RENAMED_ITEM = "Power bank 20k"
        const val LEGACY_MOVE_UP = "Move item up"

        // Matches both singular/plural forms of checklist_items_appended.
        const val SNACKBAR_FRAGMENT = "added"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
