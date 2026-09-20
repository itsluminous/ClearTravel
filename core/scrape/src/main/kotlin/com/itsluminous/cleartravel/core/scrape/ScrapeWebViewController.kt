package com.itsluminous.cleartravel.core.scrape

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONTokener

/**
 * THIN WebView host wiring a caller-owned [WebView] to a [RuleDrivenScrapeSession]
 * (ADR-008): load URL → inject prefill → optional auto-submit → poll the ready
 * signal → dump `outerHTML` → hand it to the session. All parsing intelligence lives
 * in the pure, fixture-tested [RuleExtractor]; this class is deliberately dumb and is
 * verified by instrumented tests later, not unit tests.
 *
 * The WebView's lifecycle stays with the caller's composable — call [stop] when the
 * composable leaves composition.
 */
class ScrapeWebViewController(
    private val webView: WebView,
    private val session: RuleDrivenScrapeSession,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var elapsedMillis = 0L

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        webView.settings.javaScriptEnabled = true
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    if (stopped) return
                    view.evaluateJavascript(session.prefillJavaScript()) {
                        session.submitJavaScript()?.let { submit -> view.evaluateJavascript(submit, null) }
                        session.onPageReady()
                        pollReadySignal()
                    }
                }
            }
        webView.loadUrl(session.startUrl)
    }

    /** Stops polling; safe to call multiple times. */
    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
    }

    private fun pollReadySignal() {
        if (stopped) return
        if (elapsedMillis >= timeoutMillis) return
        webView.evaluateJavascript(session.readySignalJavaScript()) { value ->
            if (stopped) return@evaluateJavascript
            if (value == "true") {
                dumpHtml()
            } else {
                elapsedMillis += pollIntervalMillis
                handler.postDelayed(::pollReadySignal, pollIntervalMillis)
            }
        }
    }

    private fun dumpHtml() {
        webView.evaluateJavascript(session.dumpHtmlJavaScript()) { encoded ->
            if (stopped) return@evaluateJavascript
            val html = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
            if (html != null) {
                stop()
                session.onHtmlDumped(html)
            }
        }
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MILLIS = 500L
        const val DEFAULT_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }
}
