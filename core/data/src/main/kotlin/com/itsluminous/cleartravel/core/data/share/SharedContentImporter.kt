package com.itsluminous.cleartravel.core.data.share

import com.itsluminous.cleartravel.core.data.repository.ChecklistRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What the confirm dialog needs to say before anything is written (ADR-039). */
sealed interface ShareImportPreview {
    val name: String

    /** True when the recipient already has a LIVE entity with the payload's id → "Update …". */
    val existing: Boolean

    data class Trip(
        override val name: String,
        val itemCount: Int,
        override val existing: Boolean,
    ) : ShareImportPreview

    data class Checklist(
        override val name: String,
        val itemCount: Int,
        override val existing: Boolean,
    ) : ShareImportPreview
}

/** What was written, so the shell can land on it. */
sealed interface ShareImportResult {
    val updated: Boolean

    data class Trip(
        val tripId: String,
        override val updated: Boolean,
    ) : ShareImportResult

    data class Checklist(
        val checklistId: String,
        override val updated: Boolean,
    ) : ShareImportResult
}

/**
 * ADR-039 ID-STABLE UPSERT of shared trips and checklists. Ids in the payload are the
 * sender's ORIGINAL ids, so:
 *
 * - **Aggregate**: same id live on this device → its fields are replaced (device-local
 *   `archived` kept); no live row (never seen, OR deleted here) → inserted — a
 *   tombstoned row with that id is resurrected by the upsert, which is what "they
 *   re-shared it after I deleted it" should mean.
 * - **Items**: upsert BY ITEM ID (fields replaced, ids stable so a third share still
 *   matches), and every LIVE local item of the aggregate whose id is absent from the
 *   payload is soft-deleted (tombstoned, ADR-002) — the payload is the whole truth
 *   of the aggregate's contents. Items the recipient added themselves are therefore
 *   dropped on an update; the confirm dialog says so.
 * - **Device-local fields survive** on same-id itinerary items: `linkedJourneyId`/
 *   `linkedJourneyType` (the recipient's own journeys) and `googleEventId`.
 * - **Checklist check state is REPLACED** by the shared one (user requirement).
 * - A checklist's `tripId` is kept only when that trip exists live on this device;
 *   otherwise the checklist lands standalone (a same-id update keeps the local owner
 *   when the payload's owner is unknown here).
 *
 * Flights are NOT imported here: a [FlightSharePayload] prefills the add form.
 */
@Singleton
class SharedContentImporter
    @Inject
    constructor(
        private val tripRepository: TripRepository,
        private val itineraryRepository: ItineraryRepository,
        private val checklistRepository: ChecklistRepository,
    ) {
        suspend fun preview(payload: TripSharePayload): ShareImportPreview.Trip =
            ShareImportPreview.Trip(
                name = payload.name,
                itemCount = payload.items.size,
                existing = tripRepository.getTrip(payload.id) != null,
            )

        suspend fun preview(payload: ChecklistSharePayload): ShareImportPreview.Checklist =
            ShareImportPreview.Checklist(
                name = payload.name,
                itemCount = payload.items.size,
                existing = checklistRepository.observeChecklist(payload.id).first() != null,
            )

        suspend fun import(payload: TripSharePayload): ShareImportResult.Trip {
            val existing = tripRepository.getTrip(payload.id)
            val localItems = itineraryRepository.observeItemsForTrip(payload.id).first().associateBy { it.id }
            tripRepository.save(SharePayloadMappers.toTrip(payload, existing))
            val incoming = SharePayloadMappers.toItineraryItems(payload, localItems)
            if (incoming.isNotEmpty()) itineraryRepository.saveAll(incoming)
            val incomingIds = incoming.map { it.id }.toSet()
            localItems.keys.filterNot { it in incomingIds }.forEach { itineraryRepository.delete(it) }
            return ShareImportResult.Trip(tripId = payload.id, updated = existing != null)
        }

        suspend fun import(payload: ChecklistSharePayload): ShareImportResult.Checklist {
            val existing = checklistRepository.observeChecklist(payload.id).first()
            val localItems = checklistRepository.observeItems(payload.id).first().associateBy { it.id }
            val sharedTripPresent = payload.tripId?.let { tripRepository.getTrip(it) } != null
            val tripId =
                when {
                    sharedTripPresent -> payload.tripId
                    else -> existing?.tripId
                }
            checklistRepository.save(SharePayloadMappers.toChecklist(payload, tripId))
            val incoming = SharePayloadMappers.toChecklistItems(payload)
            if (incoming.isNotEmpty()) checklistRepository.saveItems(incoming)
            val incomingIds = incoming.map { it.id }.toSet()
            localItems.keys.filterNot { it in incomingIds }.forEach { checklistRepository.deleteItem(it) }
            return ShareImportResult.Checklist(checklistId = payload.id, updated = existing != null)
        }
    }
