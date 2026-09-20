package com.itsluminous.cleartravel.feature.flights.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.FlightRepository
import com.itsluminous.cleartravel.core.model.FlightJourney
import com.itsluminous.cleartravel.core.scrape.RuleDrivenScrapeSession
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.ScrapeEvent
import com.itsluminous.cleartravel.core.scrape.ScrapeParams
import com.itsluminous.cleartravel.core.scrape.ScrapeRule
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import com.itsluminous.cleartravel.feature.flights.checkin.CheckInRuleSource
import com.itsluminous.cleartravel.feature.flights.polling.FlightChange
import com.itsluminous.cleartravel.feature.flights.polling.FlightChangeDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** UI state of the "Check status" flow (interactive WebView scrape, ADR-013). */
sealed interface StatusCheckUiState {
    data object Loading : StatusCheckUiState

    /**
     * A rule exists — the WebView runs [session]; [waitingForUser] = manual submit.
     * [attempt] increments on retry so the host recreates the WebView + controller.
     */
    data class Scraping(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
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

    /**
     * Extraction/mapping failed or the ready signal timed out — keep the raw page
     * visible with a "data unchanged" banner + retry/close (spec fallback, defect
     * D2 parity with the trains PNR flow).
     */
    data class ParseFailed(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
        val reason: String,
    ) : StatusCheckUiState
}

/** How the most recent COMPLETED check attempt ended. */
enum class CheckOutcomeKind { UPDATED, NO_CHANGES, FAILED }

/**
 * Last-attempt outcome surfaced back on the flight detail sheet (defect D2).
 * Deliberately TRANSIENT — held in the ViewModel/composition state layer only, no DB
 * column (an attempt that changed nothing must not look like fresh data; the durable
 * "Checked <ts>" line already comes from `lastFetchedAt`, written only on success).
 */
data class CheckOutcome(
    val flightId: String,
    val kind: CheckOutcomeKind,
    val at: Instant,
)

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

        private val outcome = MutableStateFlow<CheckOutcome?>(null)

        /** Outcome of the last COMPLETED attempt; the host hands it back on close. */
        val lastOutcome: StateFlow<CheckOutcome?> = outcome.asStateFlow()

        private var flightId: String? = null
        private var currentFlight: FlightJourney? = null
        private var currentRule: ScrapeRule? = null
        private var attempt = 0
        private var scrapeJob: Job? = null

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
                currentFlight = flight
                currentRule = rule
                beginAttempt(flight, rule)
            }
        }

        /** Rebuilds a fresh session (fresh WebView via the bumped attempt key). */
        fun retry() {
            val flight = currentFlight ?: return
            val rule = currentRule ?: return
            beginAttempt(flight, rule)
        }

        private fun beginAttempt(
            flight: FlightJourney,
            rule: ScrapeRule,
        ) {
            attempt += 1
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
            state.value = StatusCheckUiState.Scraping(session, attempt)
            scrapeJob?.cancel()
            scrapeJob =
                viewModelScope.launch {
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
                    state.value = StatusCheckUiState.Scraping(session, attempt, waitingForUser = true)
                is ScrapeEvent.Extracted -> applyExtracted(event.data, flight, session)
                is ScrapeEvent.ParseFailed -> failAttempt(flight, session, event.reason)
            }
        }

        private suspend fun applyExtracted(
            data: ScrapedData,
            flight: FlightJourney,
            session: RuleDrivenScrapeSession,
        ) {
            val result = AirlineStatusMapper.map(data, flight, fetchedAt = clock.instant())
            if (result == null) {
                failAttempt(flight, session, REASON_UNMAPPABLE)
                return
            }
            repository.applyStatusResult(flight.id, result)
            val updated = repository.getFlight(flight.id) ?: return
            val changes = FlightChangeDetector.detect(old = flight, new = updated)
            if (changes.isNotEmpty()) alerts.announce(updated, changes)
            outcome.value =
                CheckOutcome(
                    flightId = flight.id,
                    kind = if (changes.isEmpty()) CheckOutcomeKind.NO_CHANGES else CheckOutcomeKind.UPDATED,
                    at = clock.instant(),
                )
            state.value = StatusCheckUiState.Done(changes)
        }

        private fun failAttempt(
            flight: FlightJourney,
            session: RuleDrivenScrapeSession,
            reason: String,
        ) {
            outcome.value =
                CheckOutcome(flightId = flight.id, kind = CheckOutcomeKind.FAILED, at = clock.instant())
            state.value = StatusCheckUiState.ParseFailed(session, attempt, reason)
        }

        internal companion object {
            const val REASON_UNMAPPABLE = "extracted-data-unmappable"
        }
    }
