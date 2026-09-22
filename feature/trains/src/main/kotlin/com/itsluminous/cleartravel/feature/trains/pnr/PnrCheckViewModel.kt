package com.itsluminous.cleartravel.feature.trains.pnr

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
import java.time.Clock
import javax.inject.Inject

/** The interactive PNR-check screen's state machine. */
sealed interface PnrCheckUiState {
    /** The `indianrail-pnr` rule is missing from this build — cannot scrape. */
    data object RuleUnavailable : PnrCheckUiState

    /**
     * A scrape attempt is running inside the visible WebView. [attempt] increments
     * on retry so the host recreates the WebView + controller.
     */
    data class Running(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
    ) : PnrCheckUiState

    /** Extraction failed — keep showing the raw page with a banner + retry/close. */
    data class ParseFailed(
        val session: RuleDrivenScrapeSession,
        val attempt: Int,
    ) : PnrCheckUiState

    /** The result was parsed and written to Room — host closes with a snackbar. */
    data class Applied(
        /** Train number from the result — lets the host chain a route fetch. */
        val trainNumber: String = "",
    ) : PnrCheckUiState
}

/**
 * Drives the full-screen, user-visible PNR check (the captcha is CONFIRMED live on
 * indianrail — recon 2026-09-20 — so this flow is foreground-only and user-submitted;
 * see ADR-011). The composable hosts the WebView; this ViewModel owns the session and
 * the write path: `ScrapedData` → [PnrStatusMapper] → `TrainRepository.applyStatusResult`.
 */
@HiltViewModel
class PnrCheckViewModel
    @Inject
    constructor(
        /** The shared registry hoisted to `core:scrape`'s `ScrapeModule` (ADR-014). */
        private val registry: RuleRegistry,
        private val repository: TrainRepository,
    ) : ViewModel() {
        private val clock: Clock = Clock.systemUTC()

        private val state = MutableStateFlow<PnrCheckUiState>(PnrCheckUiState.RuleUnavailable)
        val uiState: StateFlow<PnrCheckUiState> = state.asStateFlow()

        private var attempt = 0

        /** Builds a fresh scrape session for [pnr]; also used by the retry action. */
        fun start(pnr: String) {
            val rule = registry.ruleById(RULE_ID)
            if (rule == null) {
                state.value = PnrCheckUiState.RuleUnavailable
                return
            }
            attempt += 1
            state.value =
                PnrCheckUiState.Running(
                    session = RuleDrivenScrapeSession(rule = rule, params = ScrapeParams(pnr = pnr)),
                    attempt = attempt,
                )
        }

        /**
         * `ScrapeEvent.Extracted` handler: maps + persists. A mapper null (garbage
         * rows despite a "successful" extraction) is treated exactly like a parse
         * failure — the stored data stays unchanged and the raw page stays visible.
         */
        fun onExtracted(
            ticketId: String,
            pnr: String,
            data: ScrapedData,
        ) {
            val result = PnrStatusMapper.map(pnr = pnr, data = data, fetchedAt = clock.instant())
            if (result == null) {
                onParseFailed()
                return
            }
            viewModelScope.launch {
                repository.applyStatusResult(ticketId, result)
                state.value = PnrCheckUiState.Applied(trainNumber = result.trainNumber)
            }
        }

        /** `ScrapeEvent.ParseFailed` handler: keep the raw page + failure banner. */
        fun onParseFailed() {
            val current = state.value
            if (current is PnrCheckUiState.Running) {
                state.value = PnrCheckUiState.ParseFailed(session = current.session, attempt = current.attempt)
            }
        }

        companion object {
            const val RULE_ID = "indianrail-pnr"
        }
    }
