package com.itsluminous.cleartravel.feature.flights.status

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.scrape.RuleDrivenScrapeSession
import com.itsluminous.cleartravel.core.scrape.ScrapeWebViewController
import com.itsluminous.cleartravel.core.scrape.configureTouchScrolling
import com.itsluminous.cleartravel.feature.flights.R

/**
 * The "Check status" flow: a VISIBLE WebView driven by the rule engine (ADR-008/
 * ADR-013). Rule found → prefill + poll + extract; extraction lands in Room and the
 * screen closes. No rule (e.g. SpiceJet) → plain web search the user reads, plus
 * manual edit back on the detail sheet. Parse failure/timeout → the raw page STAYS
 * visible (single WebView call site keyed on attempt, so the Scraping→ParseFailed
 * flip does NOT reload the page) under a "data unchanged" banner with retry/close
 * (defect D2 — parity with the trains PNR flow).
 *
 * [onClose] receives the last COMPLETED attempt's outcome (or null when the user
 * bails before any attempt finished) so the caller can surface it on the detail
 * sheet + a snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCheckScreen(
    flightId: String,
    onClose: (CheckOutcome?) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightStatusCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lastOutcome by viewModel.lastOutcome.collectAsStateWithLifecycle()

    LaunchedEffect(flightId) { viewModel.start(flightId) }

    // System back mirrors the Close icon — it must carry the last outcome too, so the
    // screen owns its own handler instead of the segment's generic one.
    BackHandler { onClose(lastOutcome) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flights_check_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.Filled.Close,
                        explanationRes = R.string.flights_icon_close,
                        onClick = { onClose(lastOutcome) },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                is StatusCheckUiState.Loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    StatusBanner(R.string.flights_check_loading)
                }

                is StatusCheckUiState.Scraping -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    StatusBanner(
                        if (current.waitingForUser) {
                            R.string.flights_check_waiting_user
                        } else {
                            R.string.flights_check_scraping
                        },
                    )
                }

                is StatusCheckUiState.WebSearchFallback -> {
                    StatusBanner(R.string.flights_check_fallback_hint)
                    PlainWebView(url = current.url, modifier = Modifier.fillMaxSize())
                }

                is StatusCheckUiState.Done -> {
                    StatusBanner(R.string.flights_check_done)
                    Button(
                        onClick = { onClose(lastOutcome) },
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    ) { Text(stringResource(R.string.flights_check_close)) }
                }

                is StatusCheckUiState.ParseFailed -> {
                    ParseFailedBanner(
                        onRetry = viewModel::retry,
                        onClose = { onClose(lastOutcome) },
                    )
                }
            }

            // SINGLE WebView call site shared by Scraping and ParseFailed so the raw
            // page genuinely stays on screen (same attempt = same remembered WebView)
            // when the state flips to failed; a retry bumps attempt and reloads.
            val scrape =
                when (val current = state) {
                    is StatusCheckUiState.Scraping -> current.session to current.attempt
                    is StatusCheckUiState.ParseFailed -> current.session to current.attempt
                    else -> null
                }
            if (scrape != null) {
                ScrapeWebView(
                    session = scrape.first,
                    attempt = scrape.second,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatusBanner(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Failure banner (D2): explains the data is unchanged and offers retry/close. */
@Composable
private fun ParseFailedBanner(
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.flights_check_parse_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.flights_check_retry))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.flights_check_close))
                }
            }
        }
    }
}

/**
 * Caller-owned WebView wired to the rule session via [ScrapeWebViewController].
 * Keyed on [attempt] so a retry rebuilds the WebView and reloads the page.
 */
@Composable
private fun ScrapeWebView(
    session: RuleDrivenScrapeSession,
    attempt: Int,
    modifier: Modifier = Modifier,
) {
    key(attempt) {
        val context = LocalContext.current
        val webView = remember(attempt) { WebView(context) }
        val controller = remember(attempt) { ScrapeWebViewController(webView, session) }

        DisposableEffect(controller) {
            controller.start()
            onDispose {
                controller.stop()
                webView.destroy()
            }
        }

        AndroidView(factory = { webView }, modifier = modifier)
    }
}

/** Read-only browser for the no-rule web-search fallback. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlainWebView(
    url: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // Same D1 rationale as the scrape host: airline SPAs need localStorage.
                settings.domStorageEnabled = true
                // Same touch-scroll setup as the scrape hosts (core:scrape).
                configureTouchScrolling()
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        modifier = modifier,
    )
}
