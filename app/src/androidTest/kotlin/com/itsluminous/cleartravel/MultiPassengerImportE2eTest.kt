package com.itsluminous.cleartravel

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.feature.flights.detail.PASSENGER_ROW_TAG
import com.itsluminous.cleartravel.feature.flights.form.IMPORT_REVIEW_CONFIRM_TAG
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.flights.R as FlightsR

/**
 * ADR-047 end to end through the REAL on-device pipeline (bundled ML Kit): the
 * two-traveller OTA itinerary image (the `blr-lko-confirmation-two-pax` fixture
 * rendered as a printed page — two legs, a seat-fare row per person per leg) shared
 * into the app → booking confirmation → the review sheet lists both segments, each
 * with `2 passengers: PRAKASH KUMAR 12A, BANDANA KUMARI 12B` → Add → TWO cards
 * (`Seats 12A, 12B` / `Seats 10A, 10C`), each flight carrying both passengers and the
 * detail sheet listing both; re-importing the same file skips both flights (every
 * passenger already on board).
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MultiPassengerImportE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var flightRepository: FlightRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun twoPassengerConfirmation_reviewLists2Passengers_addsBothToEachFlight_reimportSkips() {
        shareFile(copyAsset(ASSET), "image/png")
        chooseBookingConfirmationInIntake()

        // Review step: two segments, each naming BOTH passengers with their seats.
        val reviewTitle = composeRule.activity.resources.getQuantityString(FlightsR.plurals.flights_import_review_title, 2, 2)
        composeRule.waitForText(reviewTitle)
        val sep = composeRule.string(FlightsR.string.flights_card_route_separator)
        composeRule.onNodeWithText("IX 2019 · BLR $sep LKO").assertExists()
        composeRule.onNodeWithText("IX 1542 · LKO $sep BLR").assertExists()
        composeRule
            .onNodeWithText("Thu, 29 Oct 2026 · 10:30 · PNR R87T5G · 2 passengers: $FIRST ${FIRST_SEATS[0]}, $SECOND ${SECOND_SEATS[0]}")
            .assertExists()
        composeRule
            .onNodeWithText("Sat, 31 Oct 2026 · 20:30 · PNR D2FE9V · 2 passengers: $FIRST ${FIRST_SEATS[1]}, $SECOND ${SECOND_SEATS[1]}")
            .assertExists()
        composeRule.onNode(hasTestTag(IMPORT_REVIEW_CONFIRM_TAG)).performClick()

        // Journeys/Flights: two cards with the seats joined, the batch notice, both passengers stored per flight.
        composeRule.waitForText("IX 2019")
        composeRule.waitForText("IX 1542")
        composeRule.waitForText(composeRule.activity.resources.getQuantityString(FlightsR.plurals.flights_import_batch_added, 2, 2))
        composeRule
            .onNodeWithText(
                composeRule.string(FlightsR.string.flights_card_seats, "${FIRST_SEATS[0]}, ${SECOND_SEATS[0]}"),
            ).assertExists()
        composeRule
            .onNodeWithText(
                composeRule.string(FlightsR.string.flights_card_seats, "${FIRST_SEATS[1]}, ${SECOND_SEATS[1]}"),
            ).assertExists()
        val saved = runBlocking { flightRepository.observeActive().first() }.filter { it.airlineIata == "IX" }
        assertThat(saved.map { it.flightNumber }).containsExactly("2019", "1542")
        val onward = saved.single { it.flightNumber == "2019" }
        val back = saved.single { it.flightNumber == "1542" }
        assertThat(runBlocking { flightRepository.getPassengers(onward.id) }.map { it.name to it.seat })
            .containsExactly(FIRST to FIRST_SEATS[0], SECOND to SECOND_SEATS[0])
            .inOrder()
        assertThat(runBlocking { flightRepository.getPassengers(back.id) }.map { it.name to it.seat })
            .containsExactly(FIRST to FIRST_SEATS[1], SECOND to SECOND_SEATS[1])
            .inOrder()
        assertThat(onward.seat).isEqualTo(FIRST_SEATS[0])

        // Detail sheet of the onward flight: both passengers, both seats.
        composeRule.onNodeWithText("IX 2019").performClick()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_detail_passengers))
        composeRule.onAllNodes(hasTestTag(PASSENGER_ROW_TAG)).assertCountEquals(2)
        composeRule.onNodeWithText(FIRST).assertExists()
        composeRule.onNodeWithText(SECOND).assertExists()
        composeRule
            .onNodeWithText(
                composeRule.string(FlightsR.string.flights_passenger_seat, FIRST_SEATS[0]),
                substring = true,
            ).assertExists()
        composeRule
            .onNodeWithText(
                composeRule.string(FlightsR.string.flights_passenger_seat, SECOND_SEATS[0]),
                substring = true,
            ).assertExists()

        // The same file again: every passenger is on board → both flights skipped, nothing added.
        shareFile(copyAsset(ASSET), "image/png")
        chooseBookingConfirmationInIntake()
        composeRule.waitForText(reviewTitle)
        composeRule.onNode(hasTestTag(IMPORT_REVIEW_CONFIRM_TAG)).performClick()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_import_batch_skipped_only, 2))
        composeRule.onAllNodesWithText("IX 2019").assertCountEquals(1)
        composeRule.onAllNodesWithText("IX 1542").assertCountEquals(1)
        assertThat(runBlocking { flightRepository.getPassengers(onward.id) }).hasSize(2)
        assertThat(runBlocking { flightRepository.getPassengers(back.id) }).hasSize(2)
    }

    private fun chooseBookingConfirmationInIntake() {
        composeRule.waitForText(composeRule.string(R.string.intake_title))
        composeRule.waitUntil(timeoutMillis = OCR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(composeRule.string(R.string.intake_detecting)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_booking_confirmation)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.intake_confirm)).performClick()
    }

    /** Copies a test-APK asset into the app's share cache (a FileProvider root) and returns the file. */
    private fun copyAsset(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(File(instrumentation.targetContext.cacheDir, "share").apply { mkdirs() }, name)
        instrumentation.context.assets
            .open(name)
            .use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun shareFile(
        file: File,
        mime: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private companion object {
        /** The `blr-lko-confirmation-two-pax` fixture rendered as a printed page (1900×2484). */
        const val ASSET = "blr-lko-confirmation-two-pax.png"
        const val FIRST = "PRAKASH KUMAR"
        const val SECOND = "BANDANA KUMARI"
        val FIRST_SEATS = listOf("12A", "10A")
        val SECOND_SEATS = listOf("12B", "10C")

        /** Three ML Kit passes over the page on a cold emulator. */
        const val OCR_TIMEOUT_MILLIS = 90_000L

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
