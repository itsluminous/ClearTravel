package com.itsluminous.cleartravel.feature.flights

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.repository.AttachmentRepository
import com.itsluminous.cleartravel.core.data.repository.FlightIdentity
import com.itsluminous.cleartravel.core.data.repository.FlightPassengerMatching
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.Attachment
import com.itsluminous.cleartravel.core.model.AttachmentOwnerType
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.model.FlightPassenger
import com.itsluminous.cleartravel.core.model.FlightWithPassengers
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRules
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindowSpec
import com.itsluminous.cleartravel.feature.flights.form.BoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.form.BookingConfirmationImporter
import com.itsluminous.cleartravel.feature.flights.polling.FlightChange
import com.itsluminous.cleartravel.feature.flights.status.FlightStatusAlerts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

/** In-memory [FlightRepository] mirroring the Room implementation's merge + mirror semantics (ADR-046). */
class FakeFlightRepository : FlightRepository {
    private val flights = MutableStateFlow<Map<String, FlightJourney>>(emptyMap())
    private val passengers = MutableStateFlow<Map<String, FlightPassenger>>(emptyMap())

    val savedIds: MutableList<String> = mutableListOf()
    val appliedResults: MutableList<Pair<String, FlightStatusResult>> = mutableListOf()

    fun seed(vararg journeys: FlightJourney) {
        flights.value = journeys.associateBy { it.id }
    }

    fun seedPassengers(vararg rows: FlightPassenger) {
        passengers.value = passengers.value + rows.associateBy { it.id }
    }

    /** Every live passenger row of every flight (assertions). */
    fun allPassengers(): List<FlightPassenger> =
        passengers.value.values
            .filter { it.deletedAt == null }
            .sortedBy { it.sortOrder }

    private fun livePassengers(flightId: String) =
        passengers.value.values
            .filter { it.flightId == flightId && it.deletedAt == null }
            .sortedBy { it.sortOrder }

    private fun active() = flights.map { all -> all.values.filter { !it.archived && it.deletedAt == null } }

    override fun observeActive(): Flow<List<FlightJourney>> = active()

    override fun observeArchived(): Flow<List<FlightJourney>> =
        flights.map { all ->
            all.values.filter {
                it.archived && it.deletedAt == null
            }
        }

    override fun observeActiveWithPassengers(): Flow<List<FlightWithPassengers>> =
        combine(active(), passengers) { list, _ -> list.map { FlightWithPassengers(it, livePassengers(it.id)) } }

    override fun observeArchivedWithPassengers(): Flow<List<FlightWithPassengers>> =
        combine(observeArchived(), passengers) { list, _ -> list.map { FlightWithPassengers(it, livePassengers(it.id)) } }

    override fun observePassengers(flightId: String): Flow<List<FlightPassenger>> = passengers.map { livePassengers(flightId) }

    override suspend fun getPassengers(flightId: String): List<FlightPassenger> = livePassengers(flightId)

    override suspend fun savePassengers(
        flightId: String,
        passengers: List<FlightPassenger>,
    ): List<FlightPassenger> {
        val stamped = passengers.map { it.copy(flightId = flightId, updatedAt = Instant.now()) }
        this.passengers.value = this.passengers.value + stamped.associateBy { it.id }
        syncMirror(flightId)
        return stamped
    }

    override suspend fun replacePassengers(
        flightId: String,
        passengers: List<FlightPassenger>,
    ): List<FlightPassenger> {
        val keep = passengers.map { it.id }.toSet()
        this.passengers.value =
            this.passengers.value.mapValues { (_, p) ->
                if (p.flightId == flightId && p.id !in keep && p.deletedAt == null) p.copy(deletedAt = Instant.now()) else p
            }
        return savePassengers(flightId, passengers.mapIndexed { index, p -> p.copy(sortOrder = index) })
    }

    override suspend fun deletePassenger(id: String) {
        val passenger = passengers.value[id] ?: return
        passengers.value = passengers.value + (id to passenger.copy(deletedAt = Instant.now()))
        syncMirror(passenger.flightId)
    }

    private fun syncMirror(flightId: String) {
        val current = flights.value[flightId] ?: return
        flights.value = flights.value + (flightId to FlightPassengerMatching.mirror(current, livePassengers(flightId)))
    }

    override fun observeFlight(id: String): Flow<FlightJourney?> = flights.map { it[id] }

    override suspend fun getFlight(id: String): FlightJourney? = flights.value[id]?.takeIf { it.deletedAt == null }

    override suspend fun findByFlight(
        airlineIata: String,
        flightNumber: String,
        date: LocalDate,
    ): FlightJourney? {
        val airline = FlightIdentity.normalizeAirline(airlineIata)
        val number = FlightIdentity.normalizeFlightNumber(flightNumber)
        if (airline.isEmpty() || number.isEmpty()) return null
        return flights.value.values.firstOrNull {
            it.deletedAt == null &&
                FlightIdentity.normalizeAirline(it.airlineIata) == airline &&
                FlightIdentity.normalizeFlightNumber(it.flightNumber) == number &&
                it.date == date
        }
    }

    override suspend fun save(flight: FlightJourney): FlightJourney {
        val stamped = flight.copy(updatedAt = Instant.now())
        flights.value = flights.value + (stamped.id to stamped)
        savedIds += stamped.id
        return stamped
    }

    override suspend fun applyStatusResult(
        flightId: String,
        result: FlightStatusResult,
    ) {
        appliedResults += flightId to result
        val current = flights.value[flightId] ?: return
        flights.value =
            flights.value +
            (
                flightId to
                    current.copy(
                        status = result.status,
                        schedDep = result.schedDep ?: current.schedDep,
                        schedArr = result.schedArr ?: current.schedArr,
                        estDep = result.estDep ?: current.estDep,
                        estArr = result.estArr ?: current.estArr,
                        depTerminal = result.depTerminal.ifEmpty { current.depTerminal },
                        depGate = result.depGate.ifEmpty { current.depGate },
                        arrTerminal = result.arrTerminal.ifEmpty { current.arrTerminal },
                        arrGate = result.arrGate.ifEmpty { current.arrGate },
                        baggageBelt = result.baggageBelt.ifEmpty { current.baggageBelt },
                        aircraftType = result.aircraftType.ifEmpty { current.aircraftType },
                        lastFetchedAt = result.fetchedAt,
                    )
            )
    }

    override suspend fun setArchived(
        id: String,
        archived: Boolean,
    ) {
        flights.value[id]?.let { flights.value = flights.value + (id to it.copy(archived = archived)) }
    }

    override suspend fun delete(id: String) {
        flights.value[id]?.let { flights.value = flights.value + (id to it.copy(deletedAt = Instant.now())) }
        passengers.value = passengers.value.mapValues { (_, p) -> if (p.flightId == id) p.copy(deletedAt = Instant.now()) else p }
    }
}

/** Recording [BoardingPassImporter] returning canned values. */
class FakeBoardingPassImporter(
    var extraction: BoardingPassExtraction = BoardingPassExtraction.EMPTY,
    var storedPath: String? = "/data/fake/pass.jpg",
) : BoardingPassImporter {
    val prefilled: MutableList<String> = mutableListOf()

    /** (uri, passengerId) per stored copy — ADR-046 keys files by passenger. */
    val stored: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun prefill(uriString: String): BoardingPassExtraction {
        prefilled += uriString
        return extraction
    }

    override suspend fun store(
        uriString: String,
        passengerId: String,
    ): String? {
        stored += uriString to passengerId
        // Like production: one copy PER passenger (`<passengerId>.<ext>`), unless a fixed path was forced.
        return storedPath?.let { if (perFlightPaths) "/data/passes/$passengerId.jpg" else it }
    }

    /** When true (ADR-045/046 tests) the returned path is keyed by passenger id like the real store. */
    var perFlightPaths: Boolean = false
}

/**
 * Recording [BookingConfirmationImporter] (mirrors [FakeBoardingPassImporter]).
 * When [attachSucceeds], `attach` persists a FLIGHT attachment row into
 * [attachmentRepository] like the production importer does.
 */
class FakeBookingConfirmationImporter(
    var extraction: BookingConfirmationExtraction = BookingConfirmationExtraction.EMPTY,
    var attachSucceeds: Boolean = true,
    val attachmentRepository: FakeAttachmentRepository = FakeAttachmentRepository(),
) : BookingConfirmationImporter {
    val prefilled: MutableList<String> = mutableListOf()
    val attached: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun prefill(uriString: String): BookingConfirmationExtraction {
        prefilled += uriString
        return extraction
    }

    override suspend fun attach(
        uriString: String,
        flightId: String,
    ): Attachment? {
        attached += uriString to flightId
        if (!attachSucceeds) return null
        return attachmentRepository.save(
            Attachment(
                ownerType = AttachmentOwnerType.FLIGHT,
                ownerId = flightId,
                localPath = "/data/fake/attachments/$flightId.pdf",
                mimeType = "application/pdf",
            ),
        )
    }
}

/** In-memory [AttachmentRepository]. */
class FakeAttachmentRepository : AttachmentRepository {
    private val attachments = MutableStateFlow<List<Attachment>>(emptyList())

    fun seed(vararg rows: Attachment) {
        attachments.value = rows.toList()
    }

    override fun observeForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    ): Flow<List<Attachment>> =
        attachments.map { list ->
            list.filter { it.ownerType == ownerType && it.ownerId == ownerId && it.deletedAt == null }
        }

    override suspend fun getAttachment(id: String): Attachment? = attachments.value.firstOrNull { it.id == id }

    override suspend fun getPendingDriveUploads(): List<Attachment> =
        attachments.value.filter { it.driveFileId == null && it.deletedAt == null }

    override suspend fun save(attachment: Attachment): Attachment {
        val stamped = attachment.copy(updatedAt = Instant.now())
        attachments.value = attachments.value.filterNot { it.id == stamped.id } + stamped
        return stamped
    }

    override suspend fun delete(id: String) {
        attachments.value =
            attachments.value.map { if (it.id == id) it.copy(deletedAt = Instant.now()) else it }
    }

    override suspend fun deleteForOwner(
        ownerType: AttachmentOwnerType,
        ownerId: String,
    ) {
        attachments.value =
            attachments.value.map {
                if (it.ownerType == ownerType && it.ownerId == ownerId) it.copy(deletedAt = Instant.now()) else it
            }
    }
}

/** Static [CheckInRuleSource]. */
class FakeCheckInRuleSource(
    private val rules: CheckInRules =
        CheckInRules(
            version = 1,
            defaultWindow = CheckInWindowSpec(opensHoursBefore = 48, closesHoursBefore = 1),
        ),
) : CheckInRuleSource {
    override fun load(): CheckInRules = rules
}

/** Recording [FlightStatusAlerts]. */
class FakeFlightStatusAlerts : FlightStatusAlerts {
    val announced: MutableList<Pair<FlightJourney, List<FlightChange>>> = mutableListOf()

    override fun announce(
        flight: FlightJourney,
        changes: List<FlightChange>,
    ) {
        announced += flight to changes
    }
}
