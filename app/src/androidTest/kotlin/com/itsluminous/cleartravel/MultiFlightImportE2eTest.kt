package com.itsluminous.cleartravel

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
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
import java.io.FileOutputStream
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.flights.R as FlightsR

/**
 * ADR-045 end to end through the REAL on-device pipeline (ML Kit text recognition is
 * bundled, so this is hermetic apart from the model): the recorded two-flight OTA
 * itinerary image shared into the app → "What's this file?" → booking confirmation →
 * the review sheet lists both segments → Add → two cards; re-importing skips both; a
 * rendered single boarding pass goes straight to the form with no review step.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MultiFlightImportE2eTest {
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
    fun sharedTwoFlightConfirmation_reviewLists2_addsBoth_reimportSkipsBoth() {
        shareFile(copyAsset("blr-lko-confirmation.png"), "image/png")

        chooseBookingConfirmationInIntake()

        // Review step: both segments, with the fields the user cares about.
        val reviewTitle = composeRule.activity.resources.getQuantityString(FlightsR.plurals.flights_import_review_title, 2, 2)
        composeRule.waitForText(reviewTitle)
        val sep = composeRule.string(FlightsR.string.flights_card_route_separator)
        composeRule.onNodeWithText("IX 2019 · BLR $sep LKO").assertExists()
        composeRule.onNodeWithText("IX 1542 · LKO $sep BLR").assertExists()
        composeRule.onNodeWithText("Thu, 29 Oct 2026 · 10:30 · PNR R87T5G · Seat 12A").assertExists()
        composeRule.onNodeWithText("Sat, 31 Oct 2026 · 20:30 · PNR D2FE9V · Seat 10A").assertExists()
        composeRule.onNode(hasTestTag(IMPORT_REVIEW_CONFIRM_TAG)).performClick()

        // Landed on Journeys/Flights: two cards, and the batch notice.
        composeRule.waitForText("IX 2019")
        composeRule.waitForText("IX 1542")
        composeRule.waitForText(composeRule.activity.resources.getQuantityString(FlightsR.plurals.flights_import_batch_added, 2, 2))
        val saved = runBlocking { flightRepository.observeActive().first() }.filter { it.airlineIata == "IX" }
        assertThatFlights(saved)

        // Re-import the same file: nothing written, both reported as already saved.
        shareFile(copyAsset("blr-lko-confirmation.png"), "image/png")
        chooseBookingConfirmationInIntake()
        composeRule.waitForText(reviewTitle)
        composeRule.onNode(hasTestTag(IMPORT_REVIEW_CONFIRM_TAG)).performClick()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_import_batch_skipped_only, 2))
        composeRule.onAllNodesWithText("IX 2019").assertCountEquals(1)
        composeRule.onAllNodesWithText("IX 1542").assertCountEquals(1)
    }

    /** A single rendered pass skips the review step: the form opens prefilled, as before ADR-045. */
    @Test
    fun sharedSinglePass_opensFormDirectly_noReviewStep() {
        shareFile(renderSinglePass(), "image/png")
        composeRule.waitForText(composeRule.string(R.string.intake_title))
        composeRule.waitUntil(timeoutMillis = OCR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(composeRule.string(R.string.intake_detecting)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_boarding_pass)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.intake_confirm)).performClick()

        composeRule.waitForText(composeRule.string(FlightsR.string.flights_form_title_add))
        composeRule.waitUntil(timeoutMillis = OCR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText("2345").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_prefill_source_ocr)).assertExists()
        val reviewTitle = composeRule.activity.resources.getQuantityString(FlightsR.plurals.flights_import_review_title, 1, 1)
        composeRule.onAllNodesWithText(reviewTitle).assertCountEquals(0)
    }

    private fun assertThatFlights(saved: List<com.itsluminous.cleartravel.core.model.FlightJourney>) {
        check(saved.size == 2) { "expected 2 IX journeys, got ${saved.map { it.flightNumber }}" }
        val onward = saved.single { it.flightNumber == "2019" }
        val back = saved.single { it.flightNumber == "1542" }
        check(onward.depAirport == "BLR" && onward.arrAirport == "LKO") { "onward route ${onward.depAirport}-${onward.arrAirport}" }
        check(onward.depTerminal == "2" && onward.arrTerminal == "3") { "onward terminals ${onward.depTerminal}/${onward.arrTerminal}" }
        check(onward.pnrBookingRef == "R87T5G" && onward.seat == "12A") { "onward pnr/seat ${onward.pnrBookingRef}/${onward.seat}" }
        check(back.depAirport == "LKO" && back.arrAirport == "BLR") { "return route ${back.depAirport}-${back.arrAirport}" }
        check(back.depTerminal == "3" && back.arrTerminal == "2") { "return terminals ${back.depTerminal}/${back.arrTerminal}" }
        check(back.pnrBookingRef == "D2FE9V" && back.seat == "10A") { "return pnr/seat ${back.pnrBookingRef}/${back.seat}" }
    }

    private fun chooseBookingConfirmationInIntake() {
        composeRule.waitForText(composeRule.string(R.string.intake_title))
        // Detection runs three OCR passes over the image; the options are disabled meanwhile.
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

    /** Draws a one-flight boarding pass as printed text, large enough for ML Kit to read. */
    private fun renderSinglePass(): File {
        val lines =
            listOf(
                "BOARDING PASS",
                "NAME OF PASSENGER: SHARMA/RAHUL",
                "FROM: DELHI (DEL) TO: MUMBAI (BOM)",
                "FLIGHT 6E 2345",
                "DATE: 20 SEP 2026",
                "SEAT 12A",
                "PNR: X8B2Q4",
            )
        val bitmap = Bitmap.createBitmap(1400, 120 * (lines.size + 1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 64f
                typeface = Typeface.MONOSPACE
            }
        lines.forEachIndexed { index, line -> canvas.drawText(line, 60f, 120f * (index + 1), paint) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(File(context.cacheDir, "share").apply { mkdirs() }, "single-pass.png")
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        /** Three ML Kit passes over a 1278×1898 image on a cold emulator. */
        const val OCR_TIMEOUT_MILLIS = 90_000L

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
