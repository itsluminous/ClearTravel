package com.itsluminous.cleartravel.core.data.share

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetDefinition
import com.itsluminous.cleartravel.core.data.preset.BuiltInPresetSource
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.offline.OfflineTripRepository
import com.itsluminous.cleartravel.core.database.ClearTravelDatabase
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
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
import java.time.LocalDate
import java.time.ZoneOffset

private object NoPresets : BuiltInPresetSource {
    override fun load(): List<BuiltInPresetDefinition> = emptyList()
}

/**
 * ADR-039 ID-stable upsert semantics against the real Room-backed repositories:
 * new-id insert, same-id update (fields + items), item-level upsert by id, local
 * extras tombstoned, device-local fields preserved, checked state replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedContentImporterTest {
    private lateinit var db: ClearTravelDatabase
    private lateinit var trips: OfflineTripRepository
    private lateinit var itinerary: OfflineItineraryRepository
    private lateinit var checklists: OfflineChecklistRepository
    private lateinit var importer: SharedContentImporter
    private val clock: Clock = Clock.fixed(Fixtures.NOW.plusSeconds(600), ZoneOffset.UTC)

    @Before
    fun setUp() {
        db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
        trips = OfflineTripRepository(db.tripDao(), db.itineraryDao(), db.checklistDao(), clock)
        itinerary = OfflineItineraryRepository(db.itineraryDao(), clock)
        checklists = OfflineChecklistRepository(db.checklistDao(), db.checklistPresetDao(), clock)
        importer = SharedContentImporter(trips, itinerary, checklists)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private val senderTrip = Fixtures.trip(name = "Tokyo", destination = "Japan")
    private val senderItems =
        listOf(
            Fixtures.itineraryItem(tripId = senderTrip.id, dayIndex = 0, orderInDay = 0, name = "Senso-ji"),
            Fixtures.itineraryItem(
                tripId = senderTrip.id,
                dayIndex = 0,
                orderInDay = 1,
                name = "Skytree",
                category = PlaceCategory.ACTIVITY,
            ),
            Fixtures.itineraryItem(tripId = senderTrip.id, dayIndex = 1, orderInDay = 0, name = "Tsukiji", category = PlaceCategory.FOOD),
        )
    private val tripPayload = SharePayloadMappers.toPayload(senderTrip, senderItems)

    @Test
    fun `preview reports new trip with its item count`() =
        runTest {
            val preview = importer.preview(tripPayload)

            assertThat(preview).isEqualTo(ShareImportPreview.Trip(name = "Tokyo", itemCount = 3, existing = false))
        }

    @Test
    fun `new trip id inserts the trip and every item under the ORIGINAL ids`() =
        runTest {
            val result = importer.import(tripPayload)

            assertThat(result).isEqualTo(ShareImportResult.Trip(tripId = senderTrip.id, updated = false))
            val stored = trips.getTrip(senderTrip.id)
            assertThat(stored?.name).isEqualTo("Tokyo")
            assertThat(stored?.destination).isEqualTo("Japan")
            assertThat(stored?.startDate).isEqualTo(senderTrip.startDate)
            assertThat(stored?.updatedAt).isEqualTo(clock.instant())
            val items = itinerary.observeItemsForTrip(senderTrip.id).first()
            assertThat(items.map { it.id }).containsExactlyElementsIn(senderItems.map { it.id }).inOrder()
            assertThat(items.map { it.name }).containsExactly("Senso-ji", "Skytree", "Tsukiji").inOrder()
            assertThat(items[1].category).isEqualTo(PlaceCategory.ACTIVITY)
        }

    @Test
    fun `same trip id replaces fields and items, keeps ids stable and tombstones local extras`() =
        runTest {
            importer.import(tripPayload)
            assertThat(importer.preview(tripPayload).existing).isTrue()
            // Recipient adds their own place and renames one locally.
            val localExtra = itinerary.save(Fixtures.itineraryItem(tripId = senderTrip.id, dayIndex = 2, name = "My own find"))
            itinerary.save(senderItems[0].copy(name = "Renamed locally"))

            // Sender edits: renames the trip, drops Skytree, moves Tsukiji to day 0, adds Shibuya.
            val edited =
                SharePayloadMappers.toPayload(
                    senderTrip.copy(name = "Tokyo 2026", endDate = LocalDate.parse("2026-10-01")),
                    listOf(
                        senderItems[0],
                        senderItems[2].copy(dayIndex = 0, orderInDay = 1, note = "Go early"),
                        Fixtures.itineraryItem(tripId = senderTrip.id, dayIndex = 1, orderInDay = 0, name = "Shibuya"),
                    ),
                )
            val result = importer.import(edited)

            assertThat(result).isEqualTo(ShareImportResult.Trip(tripId = senderTrip.id, updated = true))
            val trip = trips.getTrip(senderTrip.id)
            assertThat(trip?.name).isEqualTo("Tokyo 2026")
            assertThat(trip?.endDate).isEqualTo(LocalDate.parse("2026-10-01"))
            val items = itinerary.observeItemsForTrip(senderTrip.id).first()
            assertThat(items.map { it.name }).containsExactly("Senso-ji", "Tsukiji", "Shibuya").inOrder()
            // Same ids as the sender's — a third share still matches.
            assertThat(items.map { it.id }).containsExactly(senderItems[0].id, senderItems[2].id, edited.items[2].id).inOrder()
            assertThat(items[1].note).isEqualTo("Go early")
            // Skytree (dropped by sender) and the recipient's own item are tombstoned, not hard-deleted.
            assertThat(itinerary.getItem(senderItems[1].id)).isNull()
            assertThat(itinerary.getItem(localExtra.id)).isNull()
            assertThat(db.itineraryDao().getById(localExtra.id)).isNull()
            val tombstone = db.backupDao().dumpItineraryItems().single { it.id == localExtra.id }
            assertThat(tombstone.deletedAt).isEqualTo(clock.instant())
        }

    @Test
    fun `same-id itinerary item keeps the recipient's journey link and calendar id`() =
        runTest {
            importer.import(tripPayload)
            val legId = senderItems[0].id
            itinerary.save(
                (itinerary.getItem(legId)!!).copy(
                    linkedJourneyId = "recipient-train",
                    linkedJourneyType = JourneyType.TRAIN,
                    googleEventId = "recipient-event",
                ),
            )

            importer.import(SharePayloadMappers.toPayload(senderTrip, listOf(senderItems[0].copy(name = "Renamed by sender"))))

            val item = itinerary.getItem(legId)!!
            assertThat(item.name).isEqualTo("Renamed by sender")
            assertThat(item.linkedJourneyId).isEqualTo("recipient-train")
            assertThat(item.linkedJourneyType).isEqualTo(JourneyType.TRAIN)
            assertThat(item.googleEventId).isEqualTo("recipient-event")
        }

    @Test
    fun `update keeps the recipient's archived flag`() =
        runTest {
            importer.import(tripPayload)
            trips.setArchived(senderTrip.id, true)

            importer.import(tripPayload)

            assertThat(trips.getTrip(senderTrip.id)?.archived).isTrue()
        }

    @Test
    fun `re-share after a local delete resurrects the trip as a fresh insert`() =
        runTest {
            importer.import(tripPayload)
            trips.delete(senderTrip.id)
            assertThat(importer.preview(tripPayload).existing).isFalse()

            val result = importer.import(tripPayload)

            assertThat(result.updated).isFalse()
            assertThat(trips.getTrip(senderTrip.id)?.deletedAt).isNull()
            assertThat(itinerary.observeItemsForTrip(senderTrip.id).first()).hasSize(3)
        }

    private val senderChecklist = Fixtures.checklist(name = "Tokyo packing", tripId = senderTrip.id)
    private val senderChecklistItems =
        listOf(
            Fixtures.checklistItem(checklistId = senderChecklist.id, text = "Passport", checked = true, sortOrder = 0),
            Fixtures.checklistItem(checklistId = senderChecklist.id, text = "Charger", checked = false, sortOrder = 1),
            Fixtures.checklistItem(checklistId = senderChecklist.id, text = "JR Pass", checked = false, sortOrder = 2),
        )
    private val checklistPayload = SharePayloadMappers.toPayload(senderChecklist, senderChecklistItems)

    @Test
    fun `new checklist inserts items with the shared checked state`() =
        runTest {
            assertThat(importer.preview(checklistPayload))
                .isEqualTo(ShareImportPreview.Checklist(name = "Tokyo packing", itemCount = 3, existing = false))

            val result = importer.import(checklistPayload)

            assertThat(result).isEqualTo(ShareImportResult.Checklist(checklistId = senderChecklist.id, updated = false))
            val items = checklists.observeItems(senderChecklist.id).first()
            assertThat(items.map { it.id }).containsExactlyElementsIn(senderChecklistItems.map { it.id }).inOrder()
            assertThat(items.map { it.checked }).containsExactly(true, false, false).inOrder()
        }

    @Test
    fun `same checklist id replaces check states and tombstones local extra items`() =
        runTest {
            importer.import(checklistPayload)
            // Recipient ticks Charger and adds their own item.
            checklists.setItemChecked(senderChecklistItems[1].id, true)
            val mine = checklists.saveItem(Fixtures.checklistItem(checklistId = senderChecklist.id, text = "Mine", sortOrder = 9))

            // Sender ticks JR Pass, unticks Passport, renames the list.
            val edited =
                SharePayloadMappers.toPayload(
                    senderChecklist.copy(name = "Tokyo packing v2"),
                    listOf(
                        senderChecklistItems[0].copy(checked = false),
                        senderChecklistItems[1].copy(checked = false),
                        senderChecklistItems[2].copy(checked = true),
                    ),
                )
            val result = importer.import(edited)

            assertThat(result.updated).isTrue()
            assertThat(checklists.observeChecklist(senderChecklist.id).first()?.name).isEqualTo("Tokyo packing v2")
            val items = checklists.observeItems(senderChecklist.id).first()
            assertThat(items.map { it.text }).containsExactly("Passport", "Charger", "JR Pass").inOrder()
            // Recipient's tick on Charger is REPLACED by the sender's state.
            assertThat(items.map { it.checked }).containsExactly(false, false, true).inOrder()
            assertThat(items.map { it.id }).containsExactlyElementsIn(senderChecklistItems.map { it.id }).inOrder()
            assertThat(db.checklistDao().getItemById(mine.id)).isNull()
        }

    @Test
    fun `checklist owner trip is kept only when that trip exists here`() =
        runTest {
            // Trip unknown on this device → standalone.
            importer.import(checklistPayload)
            assertThat(checklists.observeChecklist(senderChecklist.id).first()?.tripId).isNull()

            // Once the trip arrives, a re-share attaches the checklist to it.
            importer.import(tripPayload)
            importer.import(checklistPayload)
            assertThat(checklists.observeChecklist(senderChecklist.id).first()?.tripId).isEqualTo(senderTrip.id)
            assertThat(checklists.observeChecklistsForTrip(senderTrip.id).first().map { it.id }).containsExactly(senderChecklist.id)
        }

    @Test
    fun `same-id update keeps a local owner when the payload's trip is unknown here`() =
        runTest {
            val localTrip = trips.save(Fixtures.trip(name = "My trip"))
            checklists.save(Fixtures.checklist(id = senderChecklist.id, name = "Old", tripId = localTrip.id))

            importer.import(checklistPayload)

            assertThat(checklists.observeChecklist(senderChecklist.id).first()?.tripId).isEqualTo(localTrip.id)
        }
}
