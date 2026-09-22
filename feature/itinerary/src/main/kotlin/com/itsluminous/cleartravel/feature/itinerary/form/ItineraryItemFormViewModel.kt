package com.itsluminous.cleartravel.feature.itinerary.form

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddRequestBus
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddResult
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.core.model.ItineraryItemType
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.core.model.PlaceCategory
import com.itsluminous.cleartravel.core.model.TrainTicket
import com.itsluminous.cleartravel.core.model.Trip
import com.itsluminous.cleartravel.feature.itinerary.DAY_INDEX_ARG
import com.itsluminous.cleartravel.feature.itinerary.ITEM_ID_ARG
import com.itsluminous.cleartravel.feature.itinerary.ItineraryMessage
import com.itsluminous.cleartravel.feature.itinerary.TRIP_ID_ARG
import com.itsluminous.cleartravel.feature.itinerary.logic.dateForDay
import com.itsluminous.cleartravel.feature.itinerary.logic.dayCount
import com.itsluminous.cleartravel.feature.itinerary.logic.dayIndexFor
import com.itsluminous.cleartravel.feature.itinerary.logic.nextOrderInDay
import com.itsluminous.cleartravel.feature.itinerary.logic.sortDayByTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Editable state of the itinerary item form (place and commute variants). */
data class ItemFormState(
    val type: ItineraryItemType = ItineraryItemType.PLACE,
    val dayIndex: Int = 0,
    val name: String = "",
    val category: PlaceCategory = PlaceCategory.SIGHT,
    val plannedTime: String = "",
    val note: String = "",
    val link: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val commuteMode: CommuteMode = CommuteMode.CAB,
    val fromName: String = "",
    val toName: String = "",
    val linkedJourneyId: String? = null,
    val linkedJourneyType: JourneyType? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/** Linkable journeys for a commute leg (read-only view over the Journeys tab data). */
data class JourneyCandidates(
    val trains: List<TrainTicket> = emptyList(),
    val flights: List<FlightJourney> = emptyList(),
) {
    val isEmpty: Boolean get() = trains.isEmpty() && flights.isEmpty()
}

/**
 * Add/edit form for one itinerary item. Injects [TrainRepository]/[FlightRepository]
 * READ-ONLY to list linkable journeys for commute legs — the stored contract remains
 * just `linkedJourneyId` + `linkedJourneyType` (ADR-004). "Add a new train/flight"
 * goes through the cross-tab [JourneyAddRequestBus] (ADR-028): this ViewModel posts
 * the request and links whatever the Journeys tab reports back; the app shell does
 * the tab switching.
 */
@HiltViewModel
class ItineraryItemFormViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        tripRepository: TripRepository,
        private val itineraryRepository: ItineraryRepository,
        private val trainRepository: TrainRepository,
        private val flightRepository: FlightRepository,
        private val journeyAddBus: JourneyAddRequestBus,
    ) : ViewModel() {
        /** Zone used to render a flight's scheduled departure as the leg's planned time (tests pin it). */
        internal var zone: ZoneId = ZoneId.systemDefault()

        private val tripId: String = checkNotNull(savedStateHandle[TRIP_ID_ARG])
        private val itemId: String? = savedStateHandle.get<String>(ITEM_ID_ARG)?.takeIf { it.isNotBlank() }
        private val initialDayIndex: Int = savedStateHandle.get<Int>(DAY_INDEX_ARG) ?: 0

        /** True when editing an existing item, false when adding. */
        val isEdit: Boolean = itemId != null

        private val trip: StateFlow<Trip?> =
            tripRepository
                .observeTrip(tripId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val itemsForTrip: StateFlow<List<ItineraryItem>> =
            itineraryRepository
                .observeItemsForTrip(tripId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /** Day slots offered by the day picker (trip length or last used day + 1). */
        val dayCount: StateFlow<Int> =
            combine(trip, itemsForTrip) { t, items -> dayCount(t, items) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

        val journeyCandidates: StateFlow<JourneyCandidates> =
            combine(trainRepository.observeActive(), flightRepository.observeActive()) { trains, flights ->
                JourneyCandidates(trains = trains, flights = flights)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JourneyCandidates())

        private val _form = MutableStateFlow(ItemFormState(dayIndex = initialDayIndex))
        val form: StateFlow<ItemFormState> = _form.asStateFlow()

        private val _message = MutableStateFlow<ItineraryMessage?>(null)
        val message: StateFlow<ItineraryMessage?> = _message.asStateFlow()

        private val _saved = MutableStateFlow(false)
        val saved: StateFlow<Boolean> = _saved.asStateFlow()

        private var existingItem: ItineraryItem? = null

        /** Nonce of the journey-add this form is waiting for (ADR-028); null = none. */
        private var pendingJourneyAdd: Long? = null

        init {
            viewModelScope.launch {
                journeyAddBus.results
                    .filter { it.nonce == pendingJourneyAdd }
                    .collect { result ->
                        pendingJourneyAdd = null
                        if (result is JourneyAddResult.Added) linkAddedJourney(result)
                    }
            }
            if (itemId != null) {
                viewModelScope.launch {
                    itineraryRepository.getItem(itemId)?.let { item ->
                        existingItem = item
                        _form.value =
                            ItemFormState(
                                type = item.type,
                                dayIndex = item.dayIndex,
                                name = item.name,
                                category = item.category,
                                plannedTime = item.plannedTime,
                                note = item.note,
                                link = item.link,
                                latitude = item.latitude,
                                longitude = item.longitude,
                                commuteMode = item.commuteMode,
                                fromName = item.fromName,
                                toName = item.toName,
                                linkedJourneyId = item.linkedJourneyId,
                                linkedJourneyType = item.linkedJourneyType,
                            )
                    }
                }
            }
        }

        /** Applies an edit to the form state (single funnel keeps the API small). */
        fun update(transform: (ItemFormState) -> ItemFormState) {
            _form.value = transform(_form.value)
        }

        /**
         * Links a train ticket to this commute leg; prefills mode and blank endpoints,
         * and moves the leg onto the journey's day when that day is within the trip.
         */
        fun linkTrain(ticket: TrainTicket) {
            _form.value =
                _form.value.copy(
                    linkedJourneyId = ticket.id,
                    linkedJourneyType = JourneyType.TRAIN,
                    commuteMode = CommuteMode.TRAIN,
                    fromName = _form.value.fromName.ifBlank { ticket.fromStation },
                    toName = _form.value.toName.ifBlank { ticket.toStation },
                    dayIndex = dayFor(ticket.journeyDate) ?: _form.value.dayIndex,
                )
        }

        /**
         * Links a flight journey to this commute leg; prefills mode, blank endpoints
         * and (when blank) the planned time from the scheduled departure, and moves
         * the leg onto the journey's day when that day is within the trip.
         */
        fun linkFlight(flight: FlightJourney) {
            _form.value =
                _form.value.copy(
                    linkedJourneyId = flight.id,
                    linkedJourneyType = JourneyType.FLIGHT,
                    commuteMode = CommuteMode.FLIGHT,
                    fromName = _form.value.fromName.ifBlank { flight.depAirport },
                    toName = _form.value.toName.ifBlank { flight.arrAirport },
                    plannedTime = _form.value.plannedTime.ifBlank { flight.schedDep?.let(::localTime).orEmpty() },
                    dayIndex = dayFor(flight.date) ?: _form.value.dayIndex,
                )
        }

        /**
         * "Add a new train/flight" (ADR-028): posts a request on the cross-tab bus. The
         * app shell takes the user to the Journeys tab's add flow and brings them back
         * here; the reported journey is then linked exactly like a picked one.
         */
        fun requestJourneyAdd(type: JourneyType) {
            pendingJourneyAdd = journeyAddBus.request(type).nonce
        }

        /** True while a journey-add posted by this form has not been answered. */
        val isAwaitingJourneyAdd: Boolean get() = pendingJourneyAdd != null

        /**
         * Called whenever the form (re)appears. A request still pending at that point
         * means the user came back by hand (tab tap) instead of finishing the add —
         * cancel it so a later add in the Journeys tab is not linked here by surprise.
         */
        fun cancelStaleJourneyAdd() {
            val nonce = pendingJourneyAdd ?: return
            if (journeyAddBus.pendingRequest.value?.nonce == nonce) {
                journeyAddBus.complete(JourneyAddResult.Cancelled(nonce))
            }
        }

        private suspend fun linkAddedJourney(result: JourneyAddResult.Added) {
            when (result.type) {
                JourneyType.TRAIN -> trainRepository.getTicket(result.journeyId)?.let(::linkTrain)
                JourneyType.FLIGHT -> flightRepository.getFlight(result.journeyId)?.let(::linkFlight)
            }
        }

        /** The trip day of [date] when it falls inside the offered day slots; null otherwise. */
        private fun dayFor(date: LocalDate?): Int? = dayIndexFor(trip.value, date, dayCount(trip.value, itemsForTrip.value))

        private fun localTime(instant: Instant): String = instant.atZone(zone).toLocalTime().format(TIME_FORMAT)

        fun clearLinkedJourney() {
            _form.value = _form.value.copy(linkedJourneyId = null, linkedJourneyType = null)
        }

        /**
         * Validates and persists the item. A place needs a name; a commute needs both
         * endpoints (its display name derives from them when blank). On success sets
         * [saved] so the UI can navigate back.
         */
        fun save() {
            val state = _form.value
            when (state.type) {
                ItineraryItemType.PLACE ->
                    if (state.name.isBlank()) {
                        _message.value = ItineraryMessage.ITEM_NAME_REQUIRED
                        return
                    }
                ItineraryItemType.COMMUTE ->
                    if (state.fromName.isBlank() || state.toName.isBlank()) {
                        _message.value = ItineraryMessage.ITEM_ROUTE_REQUIRED
                        return
                    }
            }
            viewModelScope.launch {
                val base = existingItem ?: ItineraryItem(tripId = tripId, name = "")
                val dayChanged = base.dayIndex != state.dayIndex
                val orderInDay =
                    if (existingItem == null || dayChanged) {
                        nextOrderInDay(itemsForTrip.value, state.dayIndex)
                    } else {
                        base.orderInDay
                    }
                val isCommute = state.type == ItineraryItemType.COMMUTE
                val name =
                    if (isCommute && state.name.isBlank()) {
                        "${state.fromName.trim()} - ${state.toName.trim()}"
                    } else {
                        state.name.trim()
                    }
                val plannedTime = state.plannedTime.trim()
                val candidate =
                    base.copy(
                        dayIndex = state.dayIndex,
                        date = dateForDay(trip.value, state.dayIndex),
                        orderInDay = orderInDay,
                        type = state.type,
                        name = name,
                        latitude = if (isCommute) null else state.latitude,
                        longitude = if (isCommute) null else state.longitude,
                        plannedTime = plannedTime,
                        note = state.note.trim(),
                        category = state.category,
                        link = state.link.trim(),
                        commuteMode = if (isCommute) state.commuteMode else CommuteMode.OTHER,
                        fromName = if (isCommute) state.fromName.trim() else "",
                        toName = if (isCommute) state.toName.trim() else "",
                        linkedJourneyId = if (isCommute) state.linkedJourneyId else null,
                        linkedJourneyType = if (isCommute) state.linkedJourneyType else null,
                    )
                // ADR-029 auto-sort: a new item, a day move, or a set/changed time
                // re-derives the day's order from planned times. Any other edit leaves
                // the day's explicit order (possibly hand-dragged) untouched.
                val resort = existingItem == null || dayChanged || plannedTime != base.plannedTime
                val reordered =
                    if (resort) {
                        sortDayByTime(itemsForTrip.value.filter { it.id != candidate.id } + candidate, state.dayIndex)
                    } else {
                        emptyList()
                    }
                itineraryRepository.save(reordered.firstOrNull { it.id == candidate.id } ?: candidate)
                reordered.filter { it.id != candidate.id }.takeIf { it.isNotEmpty() }?.let { itineraryRepository.saveAll(it) }
                _message.value = ItineraryMessage.ITEM_SAVED
                _saved.value = true
            }
        }

        fun consumeMessage() {
            _message.value = null
        }

        private companion object {
            val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        }
    }
