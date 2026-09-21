package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * Trains happy path (hermetic — in-memory Room, no providers/OCR touched): add a
 * ticket manually, open its card, and see the PNR in the detail sheet; adding the
 * same PNR again is refused (ADR-024).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TrainsE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun addTicketManually_detailSheetShowsPnr() {
        // Journeys tab; the Trains segment is the default.
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()

        // FAB → Manual entry.
        composeRule
            .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_add_ticket))
            .performClick()
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_add_manual)).performClick()

        // Fill the form: a valid 10-digit PNR + a train number to find the card by.
        composeRule
            .onNodeWithText(composeRule.string(TrainsR.string.trains_form_pnr))
            .performTextInput(PNR)
        composeRule
            .onNodeWithText(composeRule.string(TrainsR.string.trains_form_train_number))
            .performTextInput(TRAIN_NUMBER)
        composeRule
            .onNodeWithText(composeRule.string(TrainsR.string.trains_form_save))
            .performScrollTo()
            .performClick()

        // Back on the list: the saved card appears; open the detail sheet.
        composeRule.waitForText(TRAIN_NUMBER)
        composeRule.onNodeWithText(TRAIN_NUMBER).performClick()

        // Detail sheet shows the PNR.
        composeRule.waitForText(PNR)
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_detail_check_status)).assertExists()
    }

    /** ADR-024: a second ticket with the same PNR is refused — notice shown, one card stays. */
    @Test
    fun addSamePnrTwice_isRefusedWithNotice_andKeepsOneCard() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()

        repeat(2) {
            composeRule
                .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_add_ticket))
                .performClick()
            composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_add_manual)).performClick()
            composeRule
                .onNodeWithText(composeRule.string(TrainsR.string.trains_form_pnr))
                .performTextInput(DUPLICATE_PNR)
            composeRule
                .onNodeWithText(composeRule.string(TrainsR.string.trains_form_train_number))
                .performTextInput(DUPLICATE_TRAIN_NUMBER)
            composeRule
                .onNodeWithText(composeRule.string(TrainsR.string.trains_form_save))
                .performScrollTo()
                .performClick()
            // Back on the list (FAB visible again) — the first save wrote the card,
            // the second was refused and just returned to the list.
            composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
                composeRule
                    .onAllNodesWithContentDescription(composeRule.string(TrainsR.string.trains_add_ticket))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }

        // The refusal is explained, and exactly one card carries the train number:
        // nothing was written twice.
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_form_duplicate_pnr))
        composeRule.onAllNodesWithText(DUPLICATE_TRAIN_NUMBER).assertCountEquals(1)
    }

    private companion object {
        const val PNR = "1234567890"
        const val TRAIN_NUMBER = "12951"
        const val DUPLICATE_PNR = "5555555555"
        const val DUPLICATE_TRAIN_NUMBER = "22222"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
