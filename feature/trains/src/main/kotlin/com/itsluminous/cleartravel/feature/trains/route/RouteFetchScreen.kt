package com.itsluminous.cleartravel.feature.trains.route

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
import com.itsluminous.cleartravel.core.scrape.ScrapedData
import com.itsluminous.cleartravel.feature.trains.R

/**
 * Full-screen WebView 'Fetch route' flow (ADR-018). Unlike the PNR check, this is
 * HANDS-FREE: erail.in's schedule page is a direct GET with no captcha or consent
 * wall (recon 2026-09-21), so the user just watches the page load; extraction and
 * persistence happen in [RouteFetchViewModel] and the screen closes itself via
 * [onApplied]. On parse failure the raw page STAYS visible with a banner +
 * retry/close (spec fallback, same pattern as the PNR check).
 */
@Composable
internal fun RouteFetchScreen(
    ticketId: String,
    trainNumber: String,
    onApplied: (stationCount: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouteFetchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Start first, then observe within the same effect — the ViewModel outlives
    // the screen, so a stale `Applied` must not complete a fresh fetch on entry
    // (same hazard as the PNR check).
    LaunchedEffect(trainNumber) {
        viewModel.start(trainNumber)
        viewModel.uiState.collect { current ->
            if (current is RouteFetchUiState.Applied) onApplied(current.stationCount)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExplainableIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                explanationRes = R.string.trains_route_fetch_close,
                onClick = onClose,
            )
            Text(
                text = stringResource(R.string.trains_route_fetch_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        when (val current = state) {
            is RouteFetchUiState.RuleUnavailable -> {
                Banner(text = stringResource(R.string.trains_route_fetch_rule_missing)) {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.trains_route_fetch_close))
                    }
                }
            }
            is RouteFetchUiState.Running -> {
                Banner(text = stringResource(R.string.trains_route_fetch_instructions, current.sourceName))
                RouteWebView(
                    session = current.session,
                    attempt = current.attempt,
                    onExtracted = { data -> viewModel.onExtracted(ticketId, data) },
                    onParseFailed = viewModel::onParseFailed,
                    modifier = Modifier.weight(1f),
                )
            }
            is RouteFetchUiState.ParseFailed -> {
                Banner(text = stringResource(R.string.trains_route_fetch_parse_failed)) {
                    TextButton(onClick = viewModel::retry) {
                        Text(stringResource(R.string.trains_route_fetch_retry))
                    }
                    if (current.hasAlternateSource) {
                        TextButton(onClick = viewModel::tryAlternateSource) {
                            Text(stringResource(R.string.trains_route_fetch_alternate))
                        }
                    }
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.trains_route_fetch_close))
                    }
                }
                // The raw page stays visible below the banner (spec fallback).
                RouteWebView(
                    session = current.session,
                    attempt = current.attempt,
                    onExtracted = { data -> viewModel.onExtracted(ticketId, data) },
                    onParseFailed = viewModel::onParseFailed,
                    modifier = Modifier.weight(1f),
                )
            }
            is RouteFetchUiState.Applied -> Unit
        }
    }
}

/**
 * Hosts the caller-owned WebView wired to the session via [ScrapeWebViewController]
 * (ADR-008). Keyed on [attempt] so a retry rebuilds the WebView and reloads the page.
 * The session's `NeedsUserAction` event (emitted because the rule has no
 * submitSelector) is deliberately ignored — a direct-GET schedule page has nothing
 * for the user to do (ADR-018).
 */
@Composable
private fun RouteWebView(
    session: RuleDrivenScrapeSession,
    attempt: Int,
    onExtracted: (ScrapedData) -> Unit,
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
                    // PageReady / NeedsUserAction: hands-free flow — nothing to do.
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
