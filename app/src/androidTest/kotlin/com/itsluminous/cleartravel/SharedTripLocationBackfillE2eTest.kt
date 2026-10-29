package com.itsluminous.cleartravel

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.di.E2eLucknow
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import com.itsluminous.cleartravel.feature.itinerary.R as ItineraryR

/**
 * ADR-048, the user's report end to end: their Lucknow trip share link (five places,
 * every one a `maps.app.goo.gl` link and no coordinates) is imported, and the place
 * backfill — the REAL ladder over the captured redirects and Nominatim's recorded
 * answers ([com.itsluminous.cleartravel.di.TestItineraryModule]) — writes coordinates
 * into Room for four of them within seconds; Chota Imambada, which Nominatim does not
 * know, stays unplaced and is offered for manual placement on the map pane. Hermetic:
 * in-memory Room, no network. (Marker rendering needs Play services, so the AOSP gate
 * asserts the data; the Play emulator run in `docs/validation-report.md` shows the pins.)
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SharedTripLocationBackfillE2eTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var itineraryRepository: ItineraryRepository

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun importedLucknowTrip_getsCoordinatesFromTheLadder_andListsTheLeftover() {
        openShareLink()
        composeRule.waitForText(composeRule.string(R.string.share_import_trip_add_title))
        composeRule.onNodeWithText(composeRule.string(R.string.share_import_add)).performClick()

        // Landed on the trip; the timeline shows the shared places.
        composeRule.waitForText("Lucknow", substring = true)
        composeRule.waitForText(composeRule.string(ItineraryR.string.itinerary_view_map))

        // The backfill (kicked by the import AND by the trip screen) places four of five.
        composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) { placedItems().size == 4 }
        val placed = placedItems().associateBy { it.name.substringBefore(',') }
        assertThat(
            placed.keys,
        ).containsExactly("Bada Imambara (Main Building)", "Royal Cafe(Hazratganj)", "VW6G+7P3 The Residency", "Rumi Darwaza")
        placed.values.forEach { item ->
            assertThat(item.latitude).isWithin(0.05).of(26.86)
            assertThat(item.longitude).isWithin(0.05).of(80.93)
        }
        val leftover = itemsForTrip().single { it.latitude == null }
        assertThat(leftover.name).startsWith("Chota Imambada")

        // Map pane: no Play services on the gate emulator → the readiness notice; with Play
        // services the markers render and the leftover is listed with "Pick on map".
        composeRule.onNodeWithText(composeRule.string(ItineraryR.string.itinerary_view_map)).performClick()
        val playNotice = composeRule.string(ItineraryR.string.itinerary_map_unavailable_play)
        val pickOnMap = composeRule.string(ItineraryR.string.itinerary_map_unplaced_pick)
        composeRule.waitUntil(timeoutMillis = E2e.WAIT_TIMEOUT_MILLIS) {
            listOf(playNotice, pickOnMap).any { composeRule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun itemsForTrip() = runBlocking { itineraryRepository.observeItemsForTrip(E2eLucknow.TRIP_ID).first() }

    private fun placedItems() = itemsForTrip().filter { it.latitude != null && it.longitude != null }

    private fun openShareLink() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("cleartravel://share/trip/${E2eLucknow.SHARE_BLOB}"))
                .setPackage(context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        @JvmStatic
        @BeforeClass
        fun grantPermissions() {
            E2e.grantNotificationPermission()
        }
    }
}
