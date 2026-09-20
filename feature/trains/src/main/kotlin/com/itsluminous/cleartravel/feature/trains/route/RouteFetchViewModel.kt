package com.itsluminous.cleartravel.feature.trains.route

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.cleartravel.core.data.repository.TrainRepository
import com.itsluminous.cleartravel.core.scrape.RuleDrivenScrapeSession
import com.itsluminous.cleartravel.core.scrape.RuleRegistry
import com.itsluminous.cleartravel.core.scrape.ScrapeParams
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The route-fetch screen's state machine (mirrors the PNR check's shape). */
sealed interface RouteFetchUiState {
    /** The `erail-route` rule is missing from this build — cannot scrape. */
    data object RuleUnavailable : RouteFetchUiState

    /**
     * The hands-free scrape is running inside the visible WebView. [attempt]
     * increments on retry so the host recreates the WebView + controller.
     */
    data class Running(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
    ) : RouteFetchUiState

    /** Extraction failed — keep showing the raw page with a banner + retry/close. */
    data class ParseFailed(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
    ) : RouteFetchUiState

    /** The route was parsed and written to Room — host closes with a snackbar. */
    data class Applied(
        val stationCount: Int,
    ) : RouteFetchUiState
}

/**
 * Drives the 'Fetch route' flow (ADR-018): the erail.in schedule page needs NO user
 * interaction (direct GET, no captcha, no consent banner — recon 2026-09-21), so the
 * whole flow is hands-free: load → readySignal → extract → pure [RouteMapper] →
 * `TrainRepository.replaceRouteStops` → close. The WebView stays visible while it
 * runs (same host pattern as the PNR check); `NeedsUserAction` never fires an
 * instruction here — there is nothing for the user to do — and on `ParseFailed`
 * the raw page stays visible with a retry (spec fallback).
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

        private var attempt = 0

        /** Builds a fresh scrape session for [trainNumber]; also the retry action. */
        fun start(trainNumber: String) {
            val rule = registry.ruleById(RULE_ID)
            if (rule == null) {
                state.value = RouteFetchUiState.RuleUnavailable
                return
            }
            attempt += 1
            state.value =
                RouteFetchUiState.Running(
                    session =
                        RuleDrivenScrapeSession(
                            rule = rule,
                            params = ScrapeParams(trainNumber = trainNumber),
                        ),
                    attempt = attempt,
                )
        }

        /**
         * `ScrapeEvent.Extracted` handler: maps + persists. A mapper null (rows
         * present but no usable stations) is treated exactly like a parse failure —
         * the stored route stays unchanged and the raw page stays visible.
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
                state.value = RouteFetchUiState.Applied(stationCount = stops.size)
            }
        }

        /** `ScrapeEvent.ParseFailed` handler: keep the raw page + failure banner. */
        fun onParseFailed() {
            val current = state.value
            if (current is RouteFetchUiState.Running) {
                state.value = RouteFetchUiState.ParseFailed(session = current.session, attempt = current.attempt)
            }
        }

        companion object {
            const val RULE_ID = "erail-route"
        }
    }
