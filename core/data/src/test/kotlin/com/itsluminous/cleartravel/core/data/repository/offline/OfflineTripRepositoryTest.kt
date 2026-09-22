package com.itsluminous.cleartravel.core.data.repository.offline

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.testing.Fixtures
import com.itsluminous.cleartravel.core.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineTripRepositoryTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var trips: OfflineTripRepository
    private lateinit var itinerary: OfflineItineraryRepository
    private lateinit var checklists: OfflineChecklistRepository
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(3600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        trips = OfflineTripRepository(db.tripDao(), db.itineraryDao(), db.checklistDao(), clock)
        itinerary = OfflineItineraryRepository(db.itineraryDao(), clock)
        checklists = OfflineChecklistRepository(db.checklistDao(), db.checklistPresetDao(), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `save bumps updatedAt and round-trips`() =
        runTest {
            val saved = trips.save(Fixtures.trip(updatedAt = Fixtures.NOW))

            assertThat(saved.updatedAt).isEqualTo(clock.instant())
            assertThat(trips.getTrip(saved.id)).isEqualTo(saved)
        }

    @Test
    fun `setArchived moves the trip between active and archived and bumps updatedAt`() =
        runTest {
            val trip = trips.save(Fixtures.trip())

            trips.setArchived(trip.id, archived = true)

            assertThat(trips.observeActive().first()).isEmpty()
            val archived = trips.observeArchived().first().single()
            assertThat(archived.archived).isTrue()
            assertThat(archived.updatedAt).isEqualTo(clock.instant())
        }

    @Test
    fun `delete cascades to itinerary items and trip-scoped checklists`() =
        runTest {
            val trip = trips.save(Fixtures.trip())
            val item = itinerary.save(Fixtures.itineraryItem(tripId = trip.id))
            val checklist = checklists.save(Fixtures.checklist(tripId = trip.id))
            val checklistItem = checklists.saveItem(Fixtures.checklistItem(checklistId = checklist.id))
            val standalone = checklists.save(Fixtures.checklist(tripId = null))

            trips.delete(trip.id)

            assertThat(trips.getTrip(trip.id)).isNull()
            assertThat(itinerary.getItem(item.id)).isNull()
            assertThat(checklists.observeChecklist(checklist.id).first()).isNull()
            assertThat(checklists.observeItems(checklist.id).first()).isEmpty()
            assertThat(checklists.observeChecklist(standalone.id).first()).isNotNull()
            // suppress unused warning — the row existing (asserted absent above) is the point
            assertThat(checklistItem.id).isNotEmpty()
        }

    @Test
    fun `itinerary saveAll stamps every row with the same updatedAt`() =
        runTest {
            val items =
                itinerary.saveAll(
                    listOf(
                        Fixtures.itineraryItem(tripId = "t", orderInDay = 0, updatedAt = Fixtures.NOW),
                        Fixtures.itineraryItem(tripId = "t", orderInDay = 1, updatedAt = Fixtures.NOW),
                    ),
                )

            assertThat(items.map { it.updatedAt }.toSet()).containsExactly(clock.instant())
            assertThat(itinerary.observeItemsForTrip("t").first()).hasSize(2)
        }

    @Test
    fun `itinerary delete tombstones the item`() =
        runTest {
            val item = itinerary.save(Fixtures.itineraryItem(tripId = "t"))

            itinerary.delete(item.id)

            assertThat(itinerary.getItem(item.id)).isNull()
            assertThat(itinerary.observeItemsForTrip("t").first()).isEmpty()
        }

    /** ADR-028 reverse lookup: across trips, only live legs of THAT journey, in day order. */
    @Test
    fun `observeItemsLinkedToJourney spans trips excludes tombstones and other journeys`() =
        runTest {
            val later =
                itinerary.save(
                    Fixtures.itineraryItem(
                        tripId = "trip-b",
                        dayIndex = 3,
                        type = ItineraryItemType.COMMUTE,
                        linkedJourneyId = "train-1",
                        linkedJourneyType = JourneyType.TRAIN,
                    ),
                )
            val earlier =
                itinerary.save(
                    Fixtures.itineraryItem(
                        tripId = "trip-a",
                        dayIndex = 1,
                        type = ItineraryItemType.COMMUTE,
                        linkedJourneyId = "train-1",
                        linkedJourneyType = JourneyType.TRAIN,
                    ),
                )
            val deleted =
                itinerary.save(
                    Fixtures.itineraryItem(tripId = "trip-a", linkedJourneyId = "train-1", linkedJourneyType = JourneyType.TRAIN),
                )
            itinerary.delete(deleted.id)
            itinerary.save(
                Fixtures.itineraryItem(tripId = "trip-a", linkedJourneyId = "flight-9", linkedJourneyType = JourneyType.FLIGHT),
            )
            itinerary.save(Fixtures.itineraryItem(tripId = "trip-a"))

            val linked = itinerary.observeItemsLinkedToJourney("train-1").first()

            assertThat(linked.map { it.id }).containsExactly(earlier.id, later.id).inOrder()
            assertThat(itinerary.observeItemsLinkedToJourney("nobody").first()).isEmpty()
        }
}
