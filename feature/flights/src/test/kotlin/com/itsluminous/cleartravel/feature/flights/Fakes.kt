package com.itsluminous.cleartravel.feature.flights

import com.itsluminous.cleartravel.core.data.provider.FlightStatusResult
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRules
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInWindowSpec
import com.itsluminous.cleartravel.feature.flights.form.BoardingPassImporter
import com.itsluminous.cleartravel.feature.flights.polling.FlightChange
import com.itsluminous.cleartravel.feature.flights.status.FlightStatusAlerts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** In-memory [FlightRepository] mirroring the Room implementation's merge semantics. */
class FakeFlightRepository : FlightRepository {
    private val flights = MutableStateFlow<Map<String, FlightJourney>>(emptyMap())

    val savedIds: MutableList<String> = mutableListOf()
    val appliedResults: MutableList<Pair<String, FlightStatusResult>> = mutableListOf()

    fun seed(vararg journeys: FlightJourney) {
        flights.value = journeys.associateBy { it.id }
    }

    private fun active() = flights.map { all -> all.values.filter { !it.archived && it.deletedAt == null } }

    override fun observeActive(): Flow<List<FlightJourney>> = active()

    override fun observeArchived(): Flow<List<FlightJourney>> =
        flights.map { all ->
            all.values.filter {
                it.archived && it.deletedAt == null
            }
        }

    override fun observeFlight(id: String): Flow<FlightJourney?> = flights.map { it[id] }

    override suspend fun getFlight(id: String): FlightJourney? = flights.value[id]?.takeIf { it.deletedAt == null }

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
    }
}

/** Recording [BoardingPassImporter] returning canned values. */
class FakeBoardingPassImporter(
    var extraction: BoardingPassExtraction = BoardingPassExtraction.EMPTY,
    var storedPath: String? = "/data/fake/pass.jpg",
) : BoardingPassImporter {
    val prefilled: MutableList<String> = mutableListOf()
    val stored: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun prefill(uriString: String): BoardingPassExtraction {
        prefilled += uriString
        return extraction
    }

    override suspend fun store(
        uriString: String,
        flightId: String,
    ): String? {
        stored += uriString to flightId
        return storedPath
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
