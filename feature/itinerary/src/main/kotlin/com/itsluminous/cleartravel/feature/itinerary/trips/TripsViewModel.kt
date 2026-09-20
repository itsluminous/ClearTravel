package com.itsluminous.cleartravel.feature.itinerary.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.ItineraryMessage
import com.itsluminous.cleartravel.feature.itinerary.logic.TripFormError
import com.itsluminous.cleartravel.feature.itinerary.logic.validateTripForm
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Trips tab root: active/archived trip lists + trip CRUD (offline-first, Room-backed). */
@HiltViewModel
class TripsViewModel
    @Inject
    constructor(
        private val tripRepository: TripRepository,
    ) : ViewModel() {
        val activeTrips: StateFlow<List<Trip>> =
            tripRepository
                .observeActive()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val archivedTrips: StateFlow<List<Trip>> =
            tripRepository
                .observeArchived()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val _message = MutableStateFlow<ItineraryMessage?>(null)
        val message: StateFlow<ItineraryMessage?> = _message.asStateFlow()

        /**
         * Validates and saves a new or edited trip. Returns true when the form passed
         * validation (the caller may dismiss the form); a validation failure emits a
         * message and returns false.
         */
        fun saveTrip(
            existing: Trip?,
            name: String,
            destination: String,
            startDate: LocalDate?,
            endDate: LocalDate?,
            coverEmoji: String,
            coverColor: String,
        ): Boolean {
            val error = validateTripForm(name, startDate, endDate)
            if (error != null) {
                _message.value =
                    when (error) {
                        TripFormError.NAME_REQUIRED -> ItineraryMessage.TRIP_NAME_REQUIRED
                        TripFormError.END_BEFORE_START -> ItineraryMessage.TRIP_DATES_INVALID
                    }
                return false
            }
            viewModelScope.launch {
                val trip =
                    (existing ?: Trip(name = name.trim())).copy(
                        name = name.trim(),
                        destination = destination.trim(),
                        startDate = startDate,
                        endDate = endDate,
                        coverEmoji = coverEmoji,
                        coverColor = coverColor,
                    )
                tripRepository.save(trip)
                _message.value = ItineraryMessage.TRIP_SAVED
            }
            return true
        }

        fun deleteTrip(id: String) {
            viewModelScope.launch {
                tripRepository.delete(id)
                _message.value = ItineraryMessage.TRIP_DELETED
            }
        }

        fun setArchived(
            id: String,
            archived: Boolean,
        ) {
            viewModelScope.launch {
                tripRepository.setArchived(id, archived)
                _message.value =
                    if (archived) ItineraryMessage.TRIP_ARCHIVED else ItineraryMessage.TRIP_UNARCHIVED
            }
        }

        fun consumeMessage() {
            _message.value = null
        }
    }
