package com.itsluminous.cleartravel.feature.flights.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.core.model.FlightWithPassengers
import com.itsluminous.cleartravel.feature.flights.form.BookingConfirmationImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a flight-document row represents (drives its label + viewer title). */
enum class FlightDocumentType {
    /** A passenger's stored boarding pass (ADR-046), or the legacy `FlightJourney.boardingPassPath` file. */
    BOARDING_PASS,

    /** An [Attachment] row owned by the flight — booking confirmations (ADR-017). */
    BOOKING_CONFIRMATION,
}

/** One entry of the detail sheet's documents list; [path] opens in the viewer. */
data class FlightDocument(
    val type: FlightDocumentType,
    val path: String,
    /** Backing attachment row id; null for a boarding-pass entry. */
    val attachmentId: String? = null,
    /** The passenger a boarding pass belongs to (ADR-046); null for a legacy flight-level pass or an attachment. */
    val passenger: FlightPassenger? = null,
)

/**
 * PURE builder of the detail sheet's documents list: one boarding pass per passenger
 * (ADR-046; a legacy flight-level pass when the flight has no passenger passes) first,
 * then attachment rows. The Drive-upload engine registers boarding passes as attachment
 * rows keyed by the same local path (ADR-016), so those duplicates are skipped; rows
 * without a local copy yet (Drive-only until the restore ladder runs) are not listed.
 */
fun buildFlightDocuments(
    entry: FlightWithPassengers,
    attachments: List<Attachment>,
): List<FlightDocument> {
    val documents = mutableListOf<FlightDocument>()
    val passes = entry.boardingPasses
    passes.forEach { documents += FlightDocument(FlightDocumentType.BOARDING_PASS, it.path, passenger = it.passenger) }
    val passPaths = passes.map { it.path }.toSet()
    attachments
        .filter { it.localPath.isNotBlank() && it.localPath !in passPaths }
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
