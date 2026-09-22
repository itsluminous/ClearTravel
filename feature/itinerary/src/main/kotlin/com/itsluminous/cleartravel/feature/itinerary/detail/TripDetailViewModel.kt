package com.itsluminous.cleartravel.feature.itinerary.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.ItineraryMessage
import com.itsluminous.cleartravel.feature.itinerary.TRIP_ID_ARG
import com.itsluminous.cleartravel.feature.itinerary.logic.ItineraryDay
import com.itsluminous.cleartravel.feature.itinerary.logic.TripMapContent
import com.itsluminous.cleartravel.feature.itinerary.logic.buildTripMapContent
import com.itsluminous.cleartravel.feature.itinerary.logic.flightJourneyLabel
import com.itsluminous.cleartravel.feature.itinerary.logic.groupItemsByDay
import com.itsluminous.cleartravel.feature.itinerary.logic.reorderWithinDay
import com.itsluminous.cleartravel.feature.itinerary.logic.trainJourneyLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One trip's detail screen: the day-grouped timeline and the derived map content
 * (both projections of the same Room-observed item list), the human labels of the
 * journeys its commute legs link to, plus item-level actions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TripDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        tripRepository: TripRepository,
        private val itineraryRepository: ItineraryRepository,
        trainRepository: TrainRepository,
        flightRepository: FlightRepository,
    ) : ViewModel() {
        private val tripId: String = checkNotNull(savedStateHandle[TRIP_ID_ARG])

        val trip: StateFlow<Trip?> =
            tripRepository
                .observeTrip(tripId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        private val items: StateFlow<List<ItineraryItem>> =
            itineraryRepository
                .observeItemsForTrip(tripId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        val days: StateFlow<List<ItineraryDay>> =
            items
                .map(::groupItemsByDay)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        val mapContent: StateFlow<TripMapContent> =
            items
                .map(::buildTripMapContent)
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    TripMapContent(emptyList(), emptyList()),
                )

        /**
         * Linked-journey id → display label (`12951`, `6E 2001`), live from Room for the
         * journeys this trip's legs reference (feature modules only meet through
         * `core:data`, so the lookup is by id, read-only). A journey that no longer
         * exists has no entry — the sheet falls back to the id prefix.
         */
        val journeyLabels: StateFlow<Map<String, String>> =
            items
                .flatMapLatest { list ->
                    val links =
                        list
                            .mapNotNull { item ->
                                val id = item.linkedJourneyId ?: return@mapNotNull null
                                val type = item.linkedJourneyType ?: return@mapNotNull null
                                id to type
                            }.distinct()
                    if (links.isEmpty()) {
                        flowOf(emptyMap())
                    } else {
                        val labelFlows: List<Flow<Pair<String, String?>>> =
                            links.map { (id, type) ->
                                when (type) {
                                    JourneyType.TRAIN -> trainRepository.observeTicket(id).map { id to it?.let(::trainJourneyLabel) }
                                    JourneyType.FLIGHT -> flightRepository.observeFlight(id).map { id to it?.let(::flightJourneyLabel) }
                                }
                            }
                        combine(labelFlows) { pairs -> pairs.mapNotNull { (id, label) -> label?.let { id to it } }.toMap() }
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        private val _message = MutableStateFlow<ItineraryMessage?>(null)
        val message: StateFlow<ItineraryMessage?> = _message.asStateFlow()

        /**
         * Drag-to-reorder drop within one day (ADR-029): [from]/[to] are positions in
         * that day's displayed list. Rewrites only the rows whose order changed; a
         * same-position or out-of-range drop persists nothing.
         */
        fun reorderDay(
            dayIndex: Int,
            from: Int,
            to: Int,
        ) {
            val updates = reorderWithinDay(items.value, dayIndex, from, to)
            if (updates.isEmpty()) return
            viewModelScope.launch { itineraryRepository.saveAll(updates) }
        }

        fun deleteItem(itemId: String) {
            viewModelScope.launch {
                itineraryRepository.delete(itemId)
                _message.value = ItineraryMessage.ITEM_DELETED
            }
        }

        fun consumeMessage() {
            _message.value = null
        }
    }
