package com.itsluminous.cleartravel

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.model.TrainCoach
import com.itsluminous.cleartravel.core.model.TrainPassenger
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
import com.itsluminous.cleartravel.feature.trains.R as TrainsR

/**
 * Seat map happy path (ADR-022, hermetic — in-memory Room seeded through the real
 * [TrainRepository], no WebView/fetch touched): a CC ticket whose passenger sits in
 * coach C4 berth 32, with the live 22346 rake (`EN C1..C5 E1 C6 C7`) stored as
 * coaches. The card's seat icon must open the seat map showing the coach strip
 * (C4 at position 4), the accuracy banner, the class-resolved grid (CC → rows) and
 * berth 32 carrying the "your berth" semantics.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SeatMapE2eTest {
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
            val ticket =
                trainRepository.save(
                    TrainTicket(pnr = PNR, trainNumber = TRAIN_NUMBER, trainName = TRAIN_NAME, travelClass = "CC"),
                )
            trainRepository.savePassengers(
                listOf(TrainPassenger(ticketId = ticket.id, name = "Seat Tester", coach = COACH, seatBerth = "32")),
            )
            trainRepository.replaceCoaches(
                ticket.id,
                RAKE.mapIndexed { index, code -> TrainCoach(ticketId = ticket.id, code = code, sortOrder = index) },
            )
        }
    }

    @Test
    fun seatIcon_opensSeatMapWithStripAndHighlightedBerth() {
        composeRule.onNodeWithText(composeRule.string(R.string.nav_journeys)).performClick()
        composeRule.waitForText(TRAIN_NUMBER, substring = true)

        composeRule
            .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_card_seat_map))
            .performClick()

        // Header resolves the class from the coach code (C → CC).
        composeRule.waitForText(composeRule.string(TrainsR.string.trains_seatmap_title, COACH, CLASS_NAME))
        // Coach strip: the ticket coach sits at position 4 (engine is unnumbered).
        composeRule
            .onNodeWithContentDescription(composeRule.string(TrainsR.string.trains_seatmap_coach_box, COACH, 4))
            .assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.string(TrainsR.string.trains_seatmap_warning)).assertIsDisplayed()
        // Seating class → "Row n" bays with the first row on screen.
        val row1 = hasContentDescription(composeRule.string(TrainsR.string.trains_seatmap_row, 1))
        composeRule.onNode(row1).assertIsDisplayed()
        // The passenger's berth carries the "your berth" semantics (CC 32 = MIDDLE).
        // Bays live in a LazyColumn, so scroll the list itself until the node composes.
        val yours =
            hasContentDescription(
                composeRule.string(
                    TrainsR.string.trains_seatmap_cell_yours,
                    32,
                    composeRule.string(TrainsR.string.trains_seatmap_type_middle),
                ),
            )
        // Some "Row n" bay is always composed inside the list's viewport, so this
        // selector stays valid while the list scrolls (Row 1 itself scrolls away).
        val rowPrefix = composeRule.string(TrainsR.string.trains_seatmap_row, 1).substringBefore(' ')
        val anyRow = hasContentDescription(rowPrefix, substring = true)
        composeRule.onNode(hasScrollAction() and hasAnyDescendant(anyRow)).performScrollToNode(yours)
        composeRule.onNode(yours).assertIsDisplayed()
        // Exactly one berth is marked as the passenger's.
        composeRule.onAllNodes(yours).assertCountEquals(1)
    }

    private companion object {
        const val PNR = "4412345678"
        const val TRAIN_NUMBER = "22346"
        const val TRAIN_NAME = "Vande Bharat Exp"
        const val COACH = "C4"
        const val CLASS_NAME = "AC Chair Car"
        val RAKE = listOf("EN", "C1", "C2", "C3", "C4", "C5", "E1", "C6", "C7")

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
