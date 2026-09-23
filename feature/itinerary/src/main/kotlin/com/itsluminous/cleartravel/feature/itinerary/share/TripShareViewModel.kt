package com.itsluminous.cleartravel.feature.itinerary.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.ItineraryRepository
import com.itsluminous.cleartravel.core.data.repository.TripRepository
import com.itsluminous.cleartravel.core.data.share.ShareLinkCodec
import com.itsluminous.cleartravel.core.data.share.SharePayloadMappers
import com.itsluminous.cleartravel.core.data.share.ShareUrlResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How building a trip share link ended (ADR-039). */
sealed interface TripShareOutcome {
    /** Hand [url] to the share sheet, captioned with the trip's [tripName]. */
    data class Ready(
        val tripName: String,
        val url: String,
    ) : TripShareOutcome

    /** The link would exceed the URL ceiling — suggest sharing fewer places. */
    data object TooLong : TripShareOutcome

    /** The trip vanished under the button (deleted from elsewhere). */
    data object Missing : TripShareOutcome
}

/**
 * Builds the self-contained share link for a trip (ADR-039): trip fields + every live
 * itinerary item, journey links stripped, under the trip's ORIGINAL ids so a re-share
 * updates the recipient's copy. Shared by the trip list card and the detail top bar.
 */
@HiltViewModel
class TripShareViewModel
    @Inject
    constructor(
        private val tripRepository: TripRepository,
        private val itineraryRepository: ItineraryRepository,
    ) : ViewModel() {
        fun share(
            tripId: String,
            onOutcome: (TripShareOutcome) -> Unit,
        ) {
            viewModelScope.launch { onOutcome(buildOutcome(tripId)) }
        }

        internal suspend fun buildOutcome(tripId: String): TripShareOutcome {
            val trip = tripRepository.getTrip(tripId) ?: return TripShareOutcome.Missing
            val items = itineraryRepository.observeItemsForTrip(tripId).first()
            return when (val result = ShareLinkCodec.buildShareUrl(SharePayloadMappers.toPayload(trip, items))) {
                is ShareUrlResult.Ok -> TripShareOutcome.Ready(tripName = trip.name, url = result.url)
                is ShareUrlResult.TooLong -> TripShareOutcome.TooLong
            }
        }
    }
