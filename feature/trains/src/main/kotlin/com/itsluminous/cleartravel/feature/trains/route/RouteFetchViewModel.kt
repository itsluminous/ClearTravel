package com.itsluminous.cleartravel.feature.trains.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.scrape.RuleDrivenScrapeSession
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.ScrapeParams
import com.itsluminous.cleartravel.core.scrape.ScrapeRule
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The route-fetch screen's state machine (mirrors the PNR check's shape). */
sealed interface RouteFetchUiState {
    /** No route rule is present in this build — cannot scrape. */
    data object RuleUnavailable : RouteFetchUiState

    /**
     * The hands-free scrape is running inside the visible WebView. [attempt]
     * increments on retry so the host recreates the WebView + controller.
     */
    data class Running(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
        /** The current source's display name (e.g. "ixigo Train Route"). */
        val sourceName: String,
        /** True when another route source exists to cycle to on failure. */
        val hasAlternateSource: Boolean,
    ) : RouteFetchUiState

    /** Extraction failed — keep showing the raw page with a banner + retry/close. */
    data class ParseFailed(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
        val sourceName: String,
        val hasAlternateSource: Boolean,
    ) : RouteFetchUiState

    /** The route was parsed and written to Room — host closes with a snackbar. */
    data class Applied(
        val stationCount: Int,
    ) : RouteFetchUiState
}

/**
 * Drives the 'Fetch route' flow (ADR-018/ADR-019): both route sources are direct-GET
 * schedule pages needing NO user interaction (no captcha, no consent wall — recon
 * 2026-09-21), so the whole flow is hands-free: load → readySignal → extract → pure
 * [RouteMapper] → `TrainRepository.replaceRouteStops` → close. Sources are tried in
 * [RULE_IDS] priority order — ixigo PRIMARY (day column + richer data), erail.in
 * fallback — and on ParseFailed the banner offers 'Try another source' which cycles
 * to the next rule (ADR-019). The WebView stays visible while it runs (same host
 * pattern as the PNR check).
 */
@HiltViewModel
class RouteFetchViewModel
    @Inject
    constructor(
        private val registry: RuleRegistry,
        private val repository: TrainRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow<RouteFetchUiState>(RouteFetchUiState.RuleUnavailable)
        val uiState: StateFlow<RouteFetchUiState> = state.asStateFlow()

        private var rules: List<ScrapeRule> = emptyList()
        private var ruleIndex = 0
        private var trainNumber = ""
        private var attempt = 0

        /** Entry point: (re)starts the flow from the PRIMARY source. */
        fun start(trainNumber: String) {
            this.trainNumber = trainNumber
            rules = RULE_IDS.mapNotNull(registry::ruleById)
            ruleIndex = 0
            if (rules.isEmpty()) {
                state.value = RouteFetchUiState.RuleUnavailable
                return
            }
            launchAttempt()
        }

        /** Re-runs the CURRENT source with a fresh WebView. */
        fun retry() {
            if (rules.isEmpty()) return
            launchAttempt()
        }

        /** Cycles to the next source in priority order and runs it (ADR-019). */
        fun tryAlternateSource() {
            if (rules.size < 2) return
            ruleIndex = (ruleIndex + 1) % rules.size
            launchAttempt()
        }

        private fun launchAttempt() {
            val rule = rules[ruleIndex]
            attempt += 1
            state.value =
                RouteFetchUiState.Running(
                    session =
                        RuleDrivenScrapeSession(
                            rule = rule,
                            params = ScrapeParams(trainNumber = trainNumber),
                        ),
                    attempt = attempt,
                    sourceName = rule.displayName,
                    hasAlternateSource = rules.size > 1,
                )
        }

        /**
         * `ScrapeEvent.Extracted` handler: maps + persists. A mapper null (rows
         * present but no usable stations) is treated exactly like a parse failure —
         * the stored route stays unchanged and the raw page stays visible.
         * A blank ticket `trainName` is backfilled from the rule's extracted
         * `trainName` field (manual tickets are often number-only) so the ticket
         * card and offline route page read "12345 · Name"; a user-entered name is
         * never overwritten.
         */
        fun onExtracted(
            ticketId: String,
            data: ScrapedData,
        ) {
            val stops = RouteMapper.map(ticketId = ticketId, data = data)
            if (stops == null) {
                onParseFailed()
                return
            }
            viewModelScope.launch {
                repository.replaceRouteStops(ticketId, stops)
                backfillTrainName(ticketId, data)
                state.value = RouteFetchUiState.Applied(stationCount = stops.size)
            }
        }

        private suspend fun backfillTrainName(
            ticketId: String,
            data: ScrapedData,
        ) {
            val scrapedName = data.fields[FIELD_TRAIN_NAME].orEmpty().trim()
            if (scrapedName.isEmpty()) return
            val ticket = repository.getTicket(ticketId) ?: return
            if (ticket.trainName.isNotBlank()) return
            repository.save(ticket.copy(trainName = scrapedName))
        }

        /** `ScrapeEvent.ParseFailed` handler: keep the raw page + failure banner. */
        fun onParseFailed() {
            val current = state.value
            if (current is RouteFetchUiState.Running) {
                state.value =
                    RouteFetchUiState.ParseFailed(
                        session = current.session,
                        attempt = current.attempt,
                        sourceName = current.sourceName,
                        hasAlternateSource = current.hasAlternateSource,
                    )
            }
        }

        companion object {
            /** Route sources in priority order — ixigo PRIMARY, erail fallback (ADR-019). */
            val RULE_IDS = listOf("ixigo-route", "erail-route")

            /** Extracted-fields key both route rules use for the train's display name. */
            private const val FIELD_TRAIN_NAME = "trainName"
        }
    }
