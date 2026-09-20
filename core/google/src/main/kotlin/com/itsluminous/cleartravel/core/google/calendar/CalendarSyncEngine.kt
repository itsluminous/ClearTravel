package com.itsluminous.cleartravel.core.google.calendar

import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.google.auth.GoogleLinkStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * One-way app → Google Calendar reconciliation (spec feature 5, ADR-016). Each pass
 * diffs the CURRENT live rows (itinerary items, train tickets, flight journeys)
 * against the [CalendarSyncStateStore] bookkeeping:
 *
 * - live row not in the store → INSERT event (or ADOPT the row's existing
 *   `googleEventId` after a backup restore), record the event id on the row via the
 *   repository and in the store;
 * - live row whose mapped-event fingerprint changed → PATCH the event;
 * - store entry whose row is no longer live (soft-deleted, or its trip was deleted —
 *   the repository cascade tombstones the items) → DELETE the event.
 *
 * The dedicated "ClearTravel" calendar is created ONCE (id cached in the link store)
 * and re-created only if it disappears server-side. Never touches the primary
 * calendar. Runs inside [CalendarSyncWorker] — never on the UI path.
 */
class CalendarSyncEngine(
    private val linkStore: GoogleLinkStore,
    private val calendarClient: CalendarClient,
    private val stateStore: CalendarSyncStateStore,
    private val tripRepository: TripRepository,
    private val itineraryRepository: ItineraryRepository,
    private val trainRepository: TrainRepository,
    private val flightRepository: FlightRepository,
    private val strings: CalendarEventStrings,
    private val calendarName: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** One row the engine wants an event for. */
    private data class SyncRow(
        val rowId: String,
        val existingEventId: String?,
        val event: CalendarEvent,
        val recordEventId: suspend (String) -> Unit,
    )

    /** Runs one full reconciliation pass. Failures bubble as [Result.failure] for worker retry. */
    suspend fun reconcile(): Result<Unit> {
        val snapshot = linkStore.current()
        if (!snapshot.isLinked || !snapshot.calendarSyncEnabled) return Result.success(Unit)
        return try {
            val calendarId = ensureCalendar(snapshot.calendarId)
            val desired = collectRows()
            val state = stateStore.all().toMutableMap()

            for (row in desired) {
                val known = state[row.rowId]
                val fingerprint = row.event.fingerprint()
                when {
                    known == null && row.existingEventId != null -> {
                        // Restored/re-linked device: the row already carries an event id
                        // (from a backup) — adopt it instead of inserting a duplicate.
                        calendarClient.updateEvent(calendarId, row.existingEventId, row.event)
                        stateStore.put(row.rowId, SyncedEventRecord(row.existingEventId, fingerprint))
                    }
                    known == null -> {
                        val eventId = calendarClient.insertEvent(calendarId, row.event)
                        row.recordEventId(eventId)
                        stateStore.put(row.rowId, SyncedEventRecord(eventId, fingerprint))
                    }
                    known.fingerprint != fingerprint -> {
                        calendarClient.updateEvent(calendarId, known.eventId, row.event)
                        stateStore.put(row.rowId, known.copy(fingerprint = fingerprint))
                    }
                }
            }

            val liveIds = desired.mapTo(mutableSetOf()) { it.rowId }
            for ((rowId, record) in state) {
                if (rowId !in liveIds) {
                    calendarClient.deleteEvent(calendarId, record.eventId)
                    stateStore.remove(rowId)
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Disconnect cleanup (spec: disconnecting offers to delete the ClearTravel
     * calendar): removes the app-created calendar — its events go with it — and the
     * local bookkeeping. Best-effort; already-gone is success.
     */
    suspend fun deleteCalendar(calendarId: String): Result<Unit> =
        try {
            calendarClient.deleteCalendar(calendarId)
            stateStore.clear()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Find-or-create of the dedicated calendar; the id is cached in the link store. */
    private suspend fun ensureCalendar(storedId: String?): String {
        if (storedId != null && calendarClient.calendarSummary(storedId) != null) return storedId
        val id = calendarClient.createCalendar(calendarName)
        linkStore.setCalendarId(id)
        return id
    }

    private suspend fun collectRows(): List<SyncRow> {
        val rows = mutableListOf<SyncRow>()
        val trips = tripRepository.observeActive().first() + tripRepository.observeArchived().first()
        for (trip in trips) {
            for (item in itineraryRepository.observeItemsForTrip(trip.id).first()) {
                val event = CalendarEventMapper.itineraryEvent(item, strings, zone) ?: continue
                rows +=
                    SyncRow(item.id, item.googleEventId, event) { id ->
                        itineraryRepository.save(item.copy(googleEventId = id))
                    }
            }
        }
        val tickets = trainRepository.observeActive().first() + trainRepository.observeArchived().first()
        for (ticket in tickets) {
            val passengers = trainRepository.observePassengers(ticket.id).first()
            val stops = trainRepository.observeRouteStops(ticket.id).first()
            val event = CalendarEventMapper.trainEvent(ticket, passengers, stops, strings, zone) ?: continue
            rows +=
                SyncRow(ticket.id, ticket.googleEventId, event) { id ->
                    trainRepository.save(ticket.copy(googleEventId = id))
                }
        }
        val flights = flightRepository.observeActive().first() + flightRepository.observeArchived().first()
        for (flight in flights) {
            val event = CalendarEventMapper.flightEvent(flight, strings, zone) ?: continue
            rows +=
                SyncRow(flight.id, flight.googleEventId, event) { id ->
                    flightRepository.save(flight.copy(googleEventId = id))
                }
        }
        return rows
    }
}
