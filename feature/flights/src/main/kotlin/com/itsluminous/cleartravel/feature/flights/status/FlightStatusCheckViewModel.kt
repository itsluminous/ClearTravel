package com.itsluminous.cleartravel.feature.flights.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.scrape.RuleDrivenScrapeSession
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.ScrapeEvent
import com.itsluminous.cleartravel.core.scrape.ScrapeParams
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.polling.FlightChange
import com.itsluminous.cleartravel.feature.flights.polling.FlightChangeDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/** UI state of the "Check status" flow (interactive WebView scrape, ADR-010). */
sealed interface StatusCheckUiState {
    data object Loading : StatusCheckUiState

    /** A rule exists — the WebView runs [session]; [waitingForUser] = manual submit. */
    data class Scraping(
        val session: RuleDrivenScrapeSession,
        val waitingForUser: Boolean = false,
    ) : StatusCheckUiState

    /** No rule for this airline (spec fallback): show a plain web search. */
    data class WebSearchFallback(
        val url: String,
    ) : StatusCheckUiState

    /** Status stored; [changes] is the old-vs-new diff (also announced as notifications). */
    data class Done(
        val changes: List<FlightChange>,
    ) : StatusCheckUiState

    /** Extraction/mapping failed — the raw page stays visible (spec fallback). */
    data class ParseFailed(
        val reason: String,
    ) : StatusCheckUiState
}

@HiltViewModel
class FlightStatusCheckViewModel
    @Inject
    constructor(
        private val repository: FlightRepository,
        private val ruleRegistry: RuleRegistry,
        private val checkInRuleSource: CheckInRuleSource,
        private val alerts: FlightStatusAlerts,
        private val clock: Clock,
    ) : ViewModel() {
        private val state = MutableStateFlow<StatusCheckUiState>(StatusCheckUiState.Loading)
        val uiState: StateFlow<StatusCheckUiState> = state

        private var flightId: String? = null

        /** Idempotent — safe to call from a LaunchedEffect. */
        fun start(id: String) {
            if (flightId == id) return
            flightId = id
            viewModelScope.launch {
                val flight = repository.getFlight(id) ?: return@launch
                val rule = ruleRegistry.flightRuleFor("${flight.airlineIata}-${flight.flightNumber}")
                if (rule == null || flight.date == null) {
                    state.value =
                        StatusCheckUiState.WebSearchFallback(
                            url =
                                FlightStatusFallbacks.webSearchUrl(
                                    airlineIata = flight.airlineIata,
                                    flightNumber = flight.flightNumber,
                                    airlineName = checkInRuleSource.load().airlineName(flight.airlineIata),
                                ),
                        )
                    return@launch
                }
                val session =
                    RuleDrivenScrapeSession(
                        rule = rule,
                        params =
                            ScrapeParams(
                                pnr = flight.pnrBookingRef,
                                flightNumber = flight.flightNumber,
                                date = FlightStatusFallbacks.formatDateForRule(rule.id, flight.date!!),
                            ),
                    )
                state.value = StatusCheckUiState.Scraping(session)
                session.events.collect { event -> onScrapeEvent(event, flight, session) }
            }
        }

        private suspend fun onScrapeEvent(
            event: ScrapeEvent,
            flight: FlightJourney,
            session: RuleDrivenScrapeSession,
        ) {
            when (event) {
                is ScrapeEvent.PageReady -> Unit
                is ScrapeEvent.NeedsUserAction ->
                    state.value = StatusCheckUiState.Scraping(session, waitingForUser = true)
                is ScrapeEvent.Extracted -> applyExtracted(event.data, flight)
                is ScrapeEvent.ParseFailed -> state.value = StatusCheckUiState.ParseFailed(event.reason)
            }
        }

        private suspend fun applyExtracted(
            data: ScrapedData,
            flight: FlightJourney,
        ) {
            val result = AirlineStatusMapper.map(data, flight, fetchedAt = clock.instant())
            if (result == null) {
                state.value = StatusCheckUiState.ParseFailed(REASON_UNMAPPABLE)
                return
            }
            repository.applyStatusResult(flight.id, result)
            val updated = repository.getFlight(flight.id) ?: return
            val changes = FlightChangeDetector.detect(old = flight, new = updated)
            if (changes.isNotEmpty()) alerts.announce(updated, changes)
            state.value = StatusCheckUiState.Done(changes)
        }

        internal companion object {
            const val REASON_UNMAPPABLE = "extracted-data-unmappable"
        }
    }
