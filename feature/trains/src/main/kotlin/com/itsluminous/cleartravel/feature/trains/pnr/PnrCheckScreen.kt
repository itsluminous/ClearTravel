package com.itsluminous.cleartravel.feature.trains.pnr

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.scrape.RuleDrivenScrapeSession
import com.itsluminous.cleartravel.core.scrape.ScrapeEvent
import com.itsluminous.cleartravel.core.scrape.ScrapeWebViewController
import com.itsluminous.cleartravel.feature.trains.R

/**
 * Full-screen, USER-VISIBLE WebView PNR check (foreground-only, user-initiated —
 * the indianrail captcha is live, ADR-011). The page opens with the PNR pre-filled
 * via injected JS; the user taps submit and solves the captcha; extraction and
 * persistence happen in [PnrCheckViewModel]. On parse failure the raw page STAYS
 * visible with a banner + retry/close (spec fallback).
 */
@Composable
internal fun PnrCheckScreen(
    ticketId: String,
    pnr: String,
    onApplied: (trainNumber: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PnrCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Start FIRST, then watch for completion in the same effect: the ViewModel is
    // scoped to the Journeys back-stack entry and outlives this screen, so a
    // stale `Applied` from an earlier check must never fire `onApplied` on
    // re-entry (it used to close the screen — and chain a route fetch for the
    // wrong train — before the page even loaded).
    LaunchedEffect(pnr) {
        viewModel.start(pnr)
        viewModel.uiState.collect { current ->
            if (current is PnrCheckUiState.Applied) onApplied(current.trainNumber)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExplainableIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                explanationRes = R.string.trains_pnr_check_close,
                onClick = onClose,
            )
            Text(
                text = stringResource(R.string.trains_pnr_check_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        when (val current = state) {
            is PnrCheckUiState.RuleUnavailable -> {
                Banner(text = stringResource(R.string.trains_pnr_check_rule_missing)) {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.trains_pnr_check_close))
                    }
                }
            }
            is PnrCheckUiState.Running -> {
                Banner(text = stringResource(R.string.trains_pnr_check_instructions))
                ScrapeWebView(
                    session = current.session,
                    attempt = current.attempt,
                    onExtracted = { data -> viewModel.onExtracted(ticketId, pnr, data) },
                    onParseFailed = viewModel::onParseFailed,
                    modifier = Modifier.weight(1f),
                )
            }
            is PnrCheckUiState.ParseFailed -> {
                Banner(text = stringResource(R.string.trains_pnr_check_parse_failed)) {
                    TextButton(onClick = { viewModel.start(pnr) }) {
                        Text(stringResource(R.string.trains_pnr_check_retry))
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.trains_pnr_check_close))
                    }
                }
                // The raw page stays visible below the banner (spec fallback).
                ScrapeWebView(
                    session = current.session,
                    attempt = current.attempt,
                    onExtracted = { data -> viewModel.onExtracted(ticketId, pnr, data) },
                    onParseFailed = viewModel::onParseFailed,
                    modifier = Modifier.weight(1f),
                )
            }
            is PnrCheckUiState.Applied -> {
                Banner(text = stringResource(R.string.trains_pnr_check_loading))
            }
        }
    }
}

/**
 * Hosts the caller-owned WebView wired to the session via [ScrapeWebViewController]
 * (ADR-008: the controller is a thin host; all parsing is pure). Keyed on [attempt]
 * so a retry rebuilds the WebView and reloads the page.
 */
@Composable
private fun ScrapeWebView(
    session: RuleDrivenScrapeSession,
    attempt: Int,
    onExtracted: (com.itsluminous.cleartravel.core.scrape.ScrapedData) -> Unit,
    onParseFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    key(attempt) {
        val context = LocalContext.current
        val webView = remember(attempt) { WebView(context) }
        val controller = remember(attempt) { ScrapeWebViewController(webView = webView, session = session) }

        LaunchedEffect(session) {
            session.events.collect { event ->
                when (event) {
                    is ScrapeEvent.Extracted -> onExtracted(event.data)
                    is ScrapeEvent.ParseFailed -> onParseFailed()
                    // PageReady / NeedsUserAction: the WebView is already visible and
                    // the instruction banner already tells the user what to do.
                    else -> Unit
                }
            }
        }
        DisposableEffect(controller) {
            controller.start()
            onDispose {
                controller.stop()
                webView.destroy()
            }
        }
        Box(modifier = modifier.fillMaxSize()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Banner(
    text: String,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (actions != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions()
                }
            }
        }
    }
}
