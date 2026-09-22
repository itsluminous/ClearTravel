package com.itsluminous.cleartravel.feature.itinerary.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.logic.MapsLinks
import com.itsluminous.cleartravel.feature.itinerary.logic.MapsPlace
import com.itsluminous.cleartravel.feature.itinerary.logic.dateForDay
import com.itsluminous.cleartravel.feature.itinerary.logic.nextOrderInDay
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the shared place goes: a brand-new trip or one the user already has. */
enum class MapsIntakeTarget {
    NEW_TRIP,
    EXISTING_TRIP,
}

/** State of the "Add place from Google Maps" intake dialog (ADR-029 part D). */
data class MapsIntakeState(
    /** True while the dialog has something to show (a shared link was received). */
    val active: Boolean = false,
    /** What the link says about the place so far; refined when a short link resolves. */
    val place: MapsPlace? = null,
    /** A short link is being expanded in the background; the dialog stays usable. */
    val resolving: Boolean = false,
    val target: MapsIntakeTarget = MapsIntakeTarget.NEW_TRIP,
    /** Name typed for the new trip; blank falls back to the place name at confirm time. */
    val newTripName: String = "",
    val selectedTripId: String? = null,
    /** True while the item is being written. */
    val saving: Boolean = false,
    /** The trip the place was added to — the shell lands there and closes the dialog. */
    val addedToTripId: String? = null,
) {
    /** Confirm is possible once the link has been read (or given up on) and a target is chosen. */
    fun canConfirm(trips: List<Trip>): Boolean =
        active &&
            !resolving &&
            !saving &&
            place != null &&
            (target == MapsIntakeTarget.NEW_TRIP || trips.any { it.id == selectedTripId })
}

/**
 * Drives the Google Maps share intake (ADR-029 part D): parse the shared link at once
 * (never blocking on network), resolve a short link in the background, let the user
 * pick "new trip" or "existing trip", then write ONE PLACE item on day 1 of that trip
 * — coordinates when the link had them, the place name when present, the original
 * link kept in the item's `link` field either way.
 */
@HiltViewModel
class MapsLinkIntakeViewModel
    @Inject
    constructor(
        private val tripRepository: TripRepository,
        private val itineraryRepository: ItineraryRepository,
        private val resolver: MapsLinkResolver,
    ) : ViewModel() {
        private val _state = MutableStateFlow(MapsIntakeState())
        val state: StateFlow<MapsIntakeState> = _state.asStateFlow()

        /** Trips offered by the "existing trip" picker. */
        val trips: StateFlow<List<Trip>> =
            tripRepository
                .observeActive()
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        private var resolveJob: Job? = null
        private var defaultTargetJob: Job? = null

        /** Set once the user picks a target themselves; the trip-list default then stays out of the way. */
        private var userChoseTarget = false

        /** Starts an intake for [sharedText]; a text without a Maps link is ignored. */
        fun start(sharedText: String) {
            val url = MapsLinks.extractUrl(sharedText) ?: return
            resolveJob?.cancel()
            defaultTargetJob?.cancel()
            userChoseTarget = false
            val parsed = MapsLinks.parse(url)
            val short = MapsLinks.isShortLink(url)
            _state.value = MapsIntakeState(active = true, place = parsed, resolving = short)
            // Default target: the first existing trip when there is one, a new trip
            // otherwise — decided from Room, not from whatever the picker has cached.
            defaultTargetJob =
                viewModelScope.launch {
                    val existing = tripRepository.observeActive().first()
                    if (!userChoseTarget && _state.value.active) {
                        _state.value =
                            _state.value.copy(
                                target = if (existing.isEmpty()) MapsIntakeTarget.NEW_TRIP else MapsIntakeTarget.EXISTING_TRIP,
                                selectedTripId = existing.firstOrNull()?.id,
                            )
                    }
                }
            if (short) {
                resolveJob =
                    viewModelScope.launch {
                        val resolved = runCatching { resolver.resolve(url) }.getOrNull()
                        val refined = resolved?.let(MapsLinks::parse)
                        _state.value =
                            _state.value.copy(
                                resolving = false,
                                // Keep the short URL as the stored link; take name/coords from the expansion.
                                place =
                                    if (refined != null) {
                                        parsed.copy(name = refined.name, latitude = refined.latitude, longitude = refined.longitude)
                                    } else {
                                        parsed
                                    },
                            )
                    }
            }
        }

        fun setTarget(target: MapsIntakeTarget) {
            userChoseTarget = true
            _state.value = _state.value.copy(target = target)
        }

        fun setNewTripName(name: String) {
            _state.value = _state.value.copy(newTripName = name)
        }

        fun selectTrip(tripId: String) {
            userChoseTarget = true
            _state.value = _state.value.copy(selectedTripId = tripId, target = MapsIntakeTarget.EXISTING_TRIP)
        }

        /**
         * Writes the place. [fallbackName] (a string resource, resolved by the UI) names
         * the item — and a new trip — when the link carried no place name.
         */
        fun confirm(fallbackName: String) {
            val current = _state.value
            val place = current.place ?: return
            if (!current.canConfirm(trips.value)) return
            _state.value = current.copy(saving = true)
            viewModelScope.launch {
                val placeName = place.name?.takeIf(String::isNotBlank) ?: fallbackName
                val tripId =
                    when (current.target) {
                        MapsIntakeTarget.NEW_TRIP ->
                            tripRepository.save(Trip(name = current.newTripName.trim().ifBlank { placeName })).id
                        MapsIntakeTarget.EXISTING_TRIP -> checkNotNull(current.selectedTripId)
                    }
                val trip = tripRepository.getTrip(tripId)
                val existing = itineraryRepository.observeItemsForTrip(tripId).first()
                itineraryRepository.save(
                    ItineraryItem(
                        tripId = tripId,
                        dayIndex = FIRST_DAY,
                        date = dateForDay(trip, FIRST_DAY),
                        orderInDay = nextOrderInDay(existing, FIRST_DAY),
                        type = ItineraryItemType.PLACE,
                        name = placeName,
                        latitude = place.latitude,
                        longitude = place.longitude,
                        category = PlaceCategory.SIGHT,
                        link = place.url,
                    ),
                )
                _state.value = _state.value.copy(saving = false, addedToTripId = tripId)
            }
        }

        /** Closes the intake (cancel, or after the shell has landed on the trip). */
        fun reset() {
            resolveJob?.cancel()
            defaultTargetJob?.cancel()
            _state.value = MapsIntakeState()
        }

        private companion object {
            /** Shared places land on day 1 by default; the user moves them from the item form. */
            const val FIRST_DAY = 0
        }
    }
