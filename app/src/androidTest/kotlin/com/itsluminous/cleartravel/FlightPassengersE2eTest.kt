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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.feature.flights.detail.PASSENGER_ROW_TAG
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
import java.time.LocalDate
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.flights.R as FlightsR

/**
 * ADR-046 end to end through the REAL on-device pipeline: a journey already carries
 * passenger A; passenger B's boarding pass for the SAME flight is shared in → the
 * prefilled form → Save → "Added B to 6E 2345" instead of "Already saved" → the
 * detail sheet shows both passengers with their seats and ONE boarding-pass document
 * (B's — A never had one). A second share of the same pass is refused as already saved.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class FlightPassengersE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var flightRepository: FlightRepository

    private lateinit var flightId: String

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            val flight =
                flightRepository.save(
                    FlightJourney(
                        airlineIata = AIRLINE,
                        flightNumber = FLIGHT_NUMBER,
                        date = LocalDate.parse("2026-09-20"),
                        depAirport = "DEL",
                        arrAirport = "BOM",
                    ),
                )
            flightId = flight.id
            flightRepository.savePassengers(
                flightId,
                listOf(FlightPassenger(flightId = flightId, name = FIRST_PASSENGER, seat = FIRST_SEAT)),
            )
        }
    }

    @Test
    fun secondPassengersPass_joinsTheExistingFlight_detailShowsBoth_reimportAlreadySaved() {
        shareFile(renderPass(), "image/png")
        chooseBoardingPassInIntake()

        // Prefilled form for the SAME flight: Save adds the passenger instead of refusing.
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_form_title_add))
        composeRule.waitUntil(timeoutMillis = OCR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(FLIGHT_NUMBER).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForText(SECOND_PASSENGER)
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_form_save)).performScrollTo().performClick()

        composeRule.waitForText(composeRule.string(FlightsR.string.flights_passenger_added, SECOND_PASSENGER, "$AIRLINE $FLIGHT_NUMBER"))
        val passengers = runBlocking { flightRepository.getPassengers(flightId) }
        assertThat(
            passengers.map { it.name to it.seat },
        ).containsExactly(FIRST_PASSENGER to FIRST_SEAT, SECOND_PASSENGER to SECOND_SEAT).inOrder()
        assertThat(passengers[0].boardingPassPath).isNull()
        assertThat(passengers[1].boardingPassPath).isNotNull()
        assertThat(File(passengers[1].boardingPassPath!!).name).startsWith(passengers[1].id)
        assertThat(runBlocking { flightRepository.observeActive().first() }).hasSize(1)

        // One card (seats joined), whose detail sheet lists both passengers and B's pass.
        composeRule.onAllNodesWithText("$AIRLINE $FLIGHT_NUMBER").assertCountEquals(1)
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_card_seats, "$FIRST_SEAT, $SECOND_SEAT")).assertExists()
        composeRule.onNodeWithText("$AIRLINE $FLIGHT_NUMBER").performClick()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_detail_passengers))
        composeRule.onAllNodes(hasTestTag(PASSENGER_ROW_TAG)).assertCountEquals(2)
        composeRule.onNodeWithText(FIRST_PASSENGER).assertExists()
        composeRule.onNodeWithText(SECOND_PASSENGER).assertExists()
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_passenger_seat, FIRST_SEAT)).assertExists()
        // B's row also shows the pass's own locator ("Seat 12A · PNR …"), so match the seat as a substring.
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_passenger_seat, SECOND_SEAT), substring = true).assertExists()
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_doc_boarding_pass_of, SECOND_PASSENGER)).assertExists()
        composeRule.onAllNodesWithContentDescription(composeRule.string(FlightsR.string.flights_passenger_view_pass)).assertCountEquals(1)

        // The same pass again: the passenger is on board → "Already saved", nothing added.
        shareFile(renderPass(), "image/png")
        chooseBoardingPassInIntake()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_form_title_add))
        composeRule.waitUntil(timeoutMillis = OCR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(SECOND_PASSENGER).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(composeRule.string(FlightsR.string.flights_form_save)).performScrollTo().performClick()
        composeRule.waitForText(composeRule.string(FlightsR.string.flights_form_duplicate))
        assertThat(runBlocking { flightRepository.getPassengers(flightId) }).hasSize(2)
    }

    private fun chooseBoardingPassInIntake() {
        composeRule.waitForText(composeRule.string(R.string.intake_title))
        composeRule.waitUntil(timeoutMillis = OCR_TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithText(composeRule.string(R.string.intake_detecting)).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(composeRule.string(R.string.intake_option_boarding_pass)).performClick()
        composeRule.onNodeWithText(composeRule.string(R.string.intake_confirm)).performClick()
    }

    /** Draws passenger B's boarding pass for the seeded flight as printed text, large enough for ML Kit. */
    private fun renderPass(): File {
        val lines =
            listOf(
                "BOARDING PASS",
                "NAME OF PASSENGER: $SECOND_PASSENGER_PRINTED",
                "FROM: DELHI (DEL) TO: MUMBAI (BOM)",
                "FLIGHT $AIRLINE $FLIGHT_NUMBER",
                "DATE: 20 SEP 2026",
                "SEAT $SECOND_SEAT",
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
        val target = File(File(context.cacheDir, "share").apply { mkdirs() }, "second-passenger-pass.png")
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
        const val AIRLINE = "6E"
        const val FLIGHT_NUMBER = "2345"
        const val FIRST_PASSENGER = "PRIYA SHARMA"
        const val FIRST_SEAT = "11C"

        /** As printed on the pass (BCBP order) — the extractor shows it as [SECOND_PASSENGER]. */
        const val SECOND_PASSENGER_PRINTED = "SHARMA/RAHUL"
        const val SECOND_PASSENGER = "RAHUL SHARMA"
        const val SECOND_SEAT = "12A"

        /** Three ML Kit passes over the rendered image on a cold emulator. */
        const val OCR_TIMEOUT_MILLIS = 90_000L

        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
