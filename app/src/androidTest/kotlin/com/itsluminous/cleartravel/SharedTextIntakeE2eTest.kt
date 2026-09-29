package com.itsluminous.cleartravel

import android.content.Intent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.itsluminous.cleartravel.feature.flights.R as FlightsR
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * ADR-042: a `text/plain` share opens the "What's this text?" intake with the
 * classifier's pick preselected; Continue routes to the confirmed feature's form,
 * prefilled from the text. Hermetic — in-memory Room, no network.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SharedTextIntakeE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun sharedFlightText_suggestsFlight_prefillsFormAndSaves() {
        shareText(AKASA_SMS)
        composeRule.waitForText(composeRule.string(R.string.intake_text_title))
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_flight)).assertIsSelected()
        composeRule.onNodeWithText(composeRule.string(R.string.intake_suggested)).assertExists()

        composeRule.onNodeWithText(composeRule.string(R.string.intake_confirm)).performClick()

        // The flight form, prefilled from the text (values live in the fields' editable text).
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_form_title_add))
        composeRule.waitForText("X4F18V")
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_prefill_source_text)).assertExists()
        listOf("QP", "1421", "2026-05-29", "X4F18V", "BLR", "VNS").forEach { value ->
            composeRule.onNodeWithText(value).assertExists()
        }
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_prefill_text_dep_terminal, "1"))
            .assertExists()

        // Plain "Save" → the shell lands on Journeys/Flights showing the new card.
        composeRule
            .onNodeWithText(composeRule.string(FlightsR.string.flights_form_save))
            .performScrollTo()
            .performClick()
        composeRule.waitForText("QP 1421")
    }

    @Test
    fun sharedTrainText_suggestsTrain_opensTrainForm() {
        shareText(IRCTC_SMS)
        composeRule.waitForText(composeRule.string(R.string.intake_text_title))
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_train_ticket)).assertIsSelected()

        composeRule.onNodeWithText(composeRule.string(R.string.intake_confirm)).performClick()

        composeRule.waitForText(composeRule.string(TrainsR.string.trains_form_title_add))
        composeRule.waitForText("8524167890")
    }

    /** The user's override wins over the suggestion: flight text sent to the train form. */
    @Test
    fun sharedFlightText_overrideToTrain_opensTrainForm() {
        shareText(AKASA_SMS)
        composeRule.waitForText(composeRule.string(R.string.intake_text_title))
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_flight)).assertIsSelected()

        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_train_ticket)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_train_ticket)).assertIsSelected()
        composeRule.onNodeWithText(composeRule.string(R.string.intake_confirm)).performClick()

        composeRule.waitForText(composeRule.string(TrainsR.string.trains_form_title_add))
    }

    private fun shareText(text: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val AKASA_SMS =
            "Dear Bandana, you have opted to auto select your seat for Akasa Air flight QP 1421 with PNR X4F18V from BLR " +
                "(Terminal 1) to VNS on 29 May 26. Your boarding pass with the assigned seat will be sent to you six hours " +
                "before flight departure. We look forward to welcoming you on board and enjoy the Akasa experience."
        const val IRCTC_SMS =
            "PNR:8524167890,TRN:12951,DOJ:20-09-25,3A,NDLS-BCT,DP:16:25,RAHUL SHARMA+1,B4 32,B4 33,CNF,Fare:4830.00"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
