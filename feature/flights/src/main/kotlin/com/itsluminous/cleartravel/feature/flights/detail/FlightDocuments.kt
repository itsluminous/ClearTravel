package com.itsluminous.cleartravel.feature.flights.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.feature.flights.form.BookingConfirmationImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a flight-document row represents (drives its label + viewer title). */
enum class FlightDocumentType {
    /** The special gate-display file referenced by `FlightJourney.boardingPassPath`. */
    BOARDING_PASS,

    /** An [Attachment] row owned by the flight — booking confirmations (ADR-017). */
    BOOKING_CONFIRMATION,
}

/** One entry of the detail sheet's documents list; [path] opens in the viewer. */
data class FlightDocument(
    val type: FlightDocumentType,
    val path: String,
    /** Backing attachment row id; null for the boarding-pass path entry. */
    val attachmentId: String? = null,
)

/**
 * PURE builder of the detail sheet's documents list: the boarding pass (from the
 * frozen `boardingPassPath` column — stays special) first, then attachment rows.
 * The Drive-upload engine registers boarding passes as attachment rows keyed by the
 * same local path (ADR-016), so those duplicates are skipped; rows without a local
 * copy yet (Drive-only until the restore ladder runs) are not listed.
 */
fun buildFlightDocuments(
    flight: FlightJourney,
    attachments: List<Attachment>,
): List<FlightDocument> {
    val documents = mutableListOf<FlightDocument>()
    flight.boardingPassPath?.let { documents += FlightDocument(FlightDocumentType.BOARDING_PASS, it) }
    attachments
        .filter { it.localPath.isNotBlank() && it.localPath != flight.boardingPassPath }
        .forEach {
            documents += FlightDocument(FlightDocumentType.BOOKING_CONFIRMATION, it.localPath, it.id)
        }
    return documents
}

/**
 * Documents state + attach action for the flight detail sheet: observes the
 * flight's FLIGHT-owned attachment rows and attaches a picked booking confirmation
 * to an EXISTING flight (picker → stored attachment → snackbar).
 */
@HiltViewModel
class FlightDocumentsViewModel
    @Inject
    constructor(
        private val attachmentRepository: AttachmentRepository,
        private val bookingImporter: BookingConfirmationImporter,
    ) : ViewModel() {
        /** Live attachment rows owned by [flightId]. */
        fun observeAttachments(flightId: String): Flow<List<Attachment>> =
            attachmentRepository.observeForOwner(AttachmentOwnerType.FLIGHT, flightId)

        /**
         * Stores the picked file as a FLIGHT attachment of [flightId];
         * [onResult] reports success for the snackbar. Never throws.
         */
        fun attachBookingConfirmation(
            flightId: String,
            uriString: String,
            onResult: (Boolean) -> Unit,
        ) {
            viewModelScope.launch {
                onResult(bookingImporter.attach(uriString, flightId) != null)
            }
        }
    }
