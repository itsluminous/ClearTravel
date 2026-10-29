package com.itsluminous.cleartravel.feature.itinerary.place

import android.util.Log
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.feature.itinerary.logic.PlaceQueries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of one trip's backfill; `total == 0` while the work list is still being read. */
data class PlaceBackfillProgress(
    val done: Int,
    val total: Int,
)

/**
 * ADR-048: fills in coordinates for places that have none — items saved from a
 * Google Maps share before this fix, shared-trip imports, hand-typed addresses —
 * through the [PlaceLocator] ladder, one item at a time (the geocoder is paced to
 * Nominatim's 1 req/s). Room is the only output: each placed item is re-read, given
 * its point and saved (the repository bumps `updatedAt`), so the map view, which
 * observes Room, fills in marker by marker.
 *
 * Idempotent and conservative: an item that is deleted or placed by the time the
 * ladder answers is left alone; an item the ladder could not place is remembered
 * for [RETRY_COOLDOWN] so a map opened five times does not fire the same failing
 * queries five times; a trip already being backfilled is not started twice; no
 * network → nothing is attempted or remembered. Callers run it in their own scope
 * (the trip screen's ViewModel, the app-open housekeeping), never on the UI thread.
 */
@Singleton
class PlaceBackfillRunner internal constructor(
    private val itineraryRepository: ItineraryRepository,
    private val locator: PlaceLocator,
    private val network: NetworkAvailability,
    private val clock: Clock,
    /** Logcat sink (`PlaceBackfill` tag) — a seam so the JVM unit tests need no Android `Log`. */
    private val log: (String) -> Unit,
) {
    @Inject
    constructor(
        itineraryRepository: ItineraryRepository,
        locator: PlaceLocator,
        network: NetworkAvailability,
        clock: Clock,
    ) : this(itineraryRepository, locator, network, clock, { Log.i(TAG, it) })

    private val mutex = Mutex()
    private val active = mutableSetOf<String>()
    private val failedAt = mutableMapOf<String, Instant>()
    private val _progress = MutableStateFlow<Map<String, PlaceBackfillProgress>>(emptyMap())

    /** Trip id → progress of its running backfill; absent once the run ends. */
    val progress: StateFlow<Map<String, PlaceBackfillProgress>> = _progress.asStateFlow()

    fun progressFor(tripId: String): Flow<PlaceBackfillProgress?> = progress.map { it[tripId] }.distinctUntilChanged()

    /** Backfills the unplaced items of ONE trip; returns how many were placed. */
    suspend fun backfill(tripId: String): Int {
        if (!network.isOnline()) return 0
        val due = dueItems(itineraryRepository.observeItemsForTrip(tripId).first())
        if (due.isEmpty()) return 0
        if (!begin(tripId)) return 0
        return try {
            place(tripId, due)
        } finally {
            end(tripId)
        }
    }

    /** Backfills every trip with unplaced items (app open); returns how many were placed. */
    suspend fun backfillAll(): Int {
        if (!network.isOnline()) return 0
        val byTrip = dueItems(itineraryRepository.getPlacesWithoutCoordinates()).groupBy { it.tripId }
        var placed = 0
        for ((tripId, items) in byTrip) {
            if (!begin(tripId)) continue
            try {
                placed += place(tripId, items)
            } finally {
                end(tripId)
            }
        }
        return placed
    }

    /** Eligible items not inside a failure cooldown — decided BEFORE a run starts, so a run with nothing to do stays silent. */
    private fun dueItems(items: List<ItineraryItem>): List<ItineraryItem> {
        val now = clock.instant()
        return items.filter(PlaceQueries::isUnplaced).filter { item ->
            failedAt[item.id]?.let { Duration.between(it, now) >= RETRY_COOLDOWN } ?: true
        }
    }

    private suspend fun place(
        tripId: String,
        due: List<ItineraryItem>,
    ): Int {
        publish(tripId, PlaceBackfillProgress(done = 0, total = due.size))
        var placed = 0
        due.forEachIndexed { index, item ->
            if (placeOne(item)) placed += 1
            publish(tripId, PlaceBackfillProgress(done = index + 1, total = due.size))
        }
        return placed
    }

    private suspend fun placeOne(item: ItineraryItem): Boolean {
        val located = locator.locate(link = item.link, text = item.name)
        if (located == null) {
            failedAt[item.id] = clock.instant()
            log("Could not place item ${item.id} (${item.name})")
            return false
        }
        // Re-read: the user may have placed, edited or deleted it while the ladder ran.
        val fresh = itineraryRepository.getItem(item.id) ?: return false
        if (!PlaceQueries.isUnplaced(fresh)) return false
        // The repository stamps `updatedAt` too; stamping here keeps the write honest against any store (ADR-002).
        itineraryRepository.save(
            fresh.copy(latitude = located.point.latitude, longitude = located.point.longitude, updatedAt = clock.instant()),
        )
        failedAt.remove(item.id)
        log("Placed ${fresh.name} at ${located.point.latitude},${located.point.longitude} via ${located.source}")
        return true
    }

    private suspend fun begin(tripId: String): Boolean =
        mutex.withLock {
            if (tripId in active) return false
            active += tripId
            _progress.value = _progress.value + (tripId to PlaceBackfillProgress(done = 0, total = 0))
            true
        }

    private suspend fun end(tripId: String) =
        mutex.withLock {
            active -= tripId
            _progress.value = _progress.value - tripId
        }

    private suspend fun publish(
        tripId: String,
        progress: PlaceBackfillProgress,
    ) = mutex.withLock { _progress.value = _progress.value + (tripId to progress) }

    companion object {
        /** An item the ladder could not place is not retried for this long within one process. */
        val RETRY_COOLDOWN: Duration = Duration.ofHours(6)
        private const val TAG = "PlaceBackfill"
    }
}
