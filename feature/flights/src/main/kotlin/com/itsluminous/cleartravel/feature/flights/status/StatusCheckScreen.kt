package com.itsluminous.cleartravel.feature.flights.status

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.itsluminous.cleartravel.feature.flights.R

/**
 * The "Check status" flow: a VISIBLE WebView driven by the rule engine (ADR-008/
 * ADR-013). Rule found → prefill + poll + extract; extraction lands in Room and the
 * screen closes. No rule (e.g. SpiceJet) → plain web search the user reads, plus
 * manual edit back on the detail sheet. Parse failure → the raw page stays visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCheckScreen(
    flightId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FlightStatusCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(flightId) { viewModel.start(flightId) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.flights_check_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.Filled.Close,
                        explanationRes = R.string.flights_icon_close,
                        onClick = onClose,
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
                    ScrapeWebView(session = current.session, modifier = Modifier.fillMaxSize())
                }

                is StatusCheckUiState.WebSearchFallback -> {
                    StatusBanner(R.string.flights_check_fallback_hint)
                    PlainWebView(url = current.url, modifier = Modifier.fillMaxSize())
                }

                is StatusCheckUiState.Done -> {
                    StatusBanner(R.string.flights_check_done)
                    Button(
                        onClick = onClose,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    ) { Text(stringResource(R.string.flights_check_close)) }
                }

                is StatusCheckUiState.ParseFailed -> {
                    // Raw page fallback (spec): the WebView content stays on screen in
                    // the Scraping composition until the state flips; from here the
                    // user reads the page manually or closes.
                    StatusBanner(R.string.flights_check_parse_failed)
                    Button(
                        onClick = onClose,
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    ) { Text(stringResource(R.string.flights_check_close)) }
                }
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

/** Caller-owned WebView wired to the rule session via [ScrapeWebViewController]. */
@Composable
private fun ScrapeWebView(
    session: RuleDrivenScrapeSession,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(session) { WebView(context) }
    val controller = remember(session) { ScrapeWebViewController(webView, session) }

    DisposableEffect(controller) {
        controller.start()
        onDispose {
            controller.stop()
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
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
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        modifier = modifier,
    )
}
