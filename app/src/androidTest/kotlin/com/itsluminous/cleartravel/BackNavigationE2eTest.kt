package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainTicket
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.itinerary.R as ItineraryR
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * System back inside the Trains segment steps back ONE level instead of popping the
 * shell NavHost to the Trips tab (user-reported defect; the segment navigates by
 * state, not by routes). Hermetic: one ticket seeded through the real repository.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class BackNavigationE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var trainRepository: TrainRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            trainRepository.save(TrainTicket(pnr = PNR, trainNumber = TRAIN_NUMBER, trainName = TRAIN_NAME))
        }
    }

    @Test
    fun detailSheet_back_staysOnTrainsList() {
        openTrainsList()
        composeRule.onNodeWithText(TRAIN_NUMBER).performClick()
        composeRule.waitForText(PNR)

        Espresso.pressBack()

        assertOnTrainsList()
    }

    @Test
    fun seatMapFromCard_back_returnsToTrainsList() {
        openTrainsList()
        composeRule
            .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_card_seat_map))
            .performClick()
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_seatmap_warning))

        Espresso.pressBack()

        assertOnTrainsList()
        // Opened from the card, so no detail sheet is re-opened.
        composeRule.onAllNodesWithText(composeRule.string(TrainsR.string.trains_detail_check_status)).assertCountEquals(0)
    }

    @Test
    fun seatMapFromDetail_back_returnsToDetailThenList() {
        openTrainsList()
        composeRule.onNodeWithText(TRAIN_NUMBER).performClick()
        composeRule.waitForText(PNR)
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_detail_seat_map)).performClick()
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_seatmap_warning))

        // One level up: the detail sheet it was opened from.
        Espresso.pressBack()
        composeRule.waitForText(PNR)
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_detail_check_status)).assertExists()

        // And once more: the list.
        Espresso.pressBack()
        assertOnTrainsList()
    }

    @Test
    fun addForm_back_cancelsToTrainsList() {
        openTrainsList()
        composeRule
            .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_add_ticket))
            .performClick()
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_add_manual)).performClick()
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_form_title_add))

        Espresso.pressBack()

        assertOnTrainsList()
    }

    private fun openTrainsList() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.waitForText(TRAIN_NUMBER)
    }

    /** Still on Journeys / Trains — the card is there and the Trips tab's FAB is not. */
    private fun assertOnTrainsList() {
        composeRule.waitForText(TRAIN_NUMBER)
        composeRule.onNodeWithText(TRAIN_NUMBER).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_add_ticket))
            .assertIsDisplayed()
        composeRule
            .onAllNodesWithContentDescription(composeRule.string(ItineraryR.string.itinerary_add_trip))
            .assertCountEquals(0)
    }

    private companion object {
        const val PNR = "8524317690"
        const val TRAIN_NUMBER = "12951"
        const val TRAIN_NAME = "Mumbai Rajdhani"

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
