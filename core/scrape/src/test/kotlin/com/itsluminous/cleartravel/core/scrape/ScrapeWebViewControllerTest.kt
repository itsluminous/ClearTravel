package com.itsluminous.cleartravel.core.scrape

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWebView
import java.util.concurrent.TimeUnit

/**
 * The THIN WebView host (ADR-008) on Robolectric's WebView shadow: the JS the
 * controller runs and the order it runs it in, the dismiss-overlay re-attempt on every
 * poll tick (D3), the ready-signal TIMEOUT dump path (D2) and the touch-scroll
 * hardening (ADR-024 §4). The shadow records `evaluateJavascript` calls and hands back
 * the callback, so each tick is driven explicitly and deterministically.
 */
@RunWith(RobolectricTestRunner::class)
class ScrapeWebViewControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val params = ScrapeParams(pnr = "8524317690")

    /** Direct-GET rule with an overlay to dismiss and a required field the dump must carry. */
    private val rule =
        RuleFixtureHarness.loadRule("indianrail-pnr.json").copy(
            urlTemplate = "https://example.com/status?pnr={pnr}",
            dismissSelectors = listOf("#consent-accept"),
            prefill = emptyList(),
            submitSelector = null,
        )

    /** A parent that records whether the WebView claimed the touch stream. */
    private class RecordingParent(
        context: Context,
    ) : FrameLayout(context) {
        var disallowIntercept = false

        override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
            this.disallowIntercept = disallowIntercept
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    /** Records EVERY script in order (the shadow keeps only the last one). */
    private class RecordingWebView(
        context: Context,
    ) : WebView(context) {
        val scripts = mutableListOf<String>()

        override fun evaluateJavascript(
            script: String,
            resultCallback: android.webkit.ValueCallback<String>?,
        ) {
            scripts += script
            super.evaluateJavascript(script, resultCallback)
        }
    }

    private class Harness(
        context: Context,
        rule: ScrapeRule,
        params: ScrapeParams,
        pollIntervalMillis: Long = 100,
        timeoutMillis: Long = 250,
    ) {
        val parent = RecordingParent(context)
        val webView = RecordingWebView(context).also(parent::addView)
        val shadow: ShadowWebView = shadowOf(webView)
        val session = RuleDrivenScrapeSession(rule, params)
        val controller = ScrapeWebViewController(webView, session, pollIntervalMillis, timeoutMillis)

        fun lastJs(): String = requireNotNull(shadow.lastEvaluatedJavascript)

        /** Answers the most recent `evaluateJavascript` with [value] (JSON-encoded like Chromium does). */
        fun answer(value: String) {
            requireNotNull(shadow.lastEvaluatedJavascriptCallback).onReceiveValue(value)
        }

        fun finishPageLoad() {
            requireNotNull(shadow.webViewClient).onPageFinished(webView, webView.url)
        }
    }

    @Test
    fun `start configures the WebView and loads the expanded URL`() {
        val h = Harness(context, rule, params)

        h.controller.start()

        assertThat(h.webView.settings.javaScriptEnabled).isTrue()
        assertThat(h.webView.settings.domStorageEnabled).isTrue()
        assertThat(h.shadow.lastLoadedUrl).isEqualTo("https://example.com/status?pnr=8524317690")
        assertThat(h.webView.isVerticalScrollBarEnabled).isTrue()
        assertThat(h.webView.isHorizontalScrollBarEnabled).isFalse()
        assertThat(h.webView.overScrollMode).isEqualTo(View.OVER_SCROLL_IF_CONTENT_SCROLLS)
    }

    @Test
    fun `touch down claims the gesture from ancestors without consuming the event`() {
        val h = Harness(context, rule, params)
        h.controller.start()
        val listener = requireNotNull(shadowOf(h.webView).onTouchListener)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)

        val consumed = listener.onTouch(h.webView, down)

        assertThat(consumed).isFalse()
        assertThat(h.parent.disallowIntercept).isTrue()
        down.recycle()
    }

    @Test
    fun `page finished runs dismiss then prefill and starts polling with dismiss on every tick`() =
        runTest {
            val h = Harness(context, rule, params)
            h.controller.start()
            h.session.events.test {
                val dismiss = requireNotNull(h.session.dismissJavaScript())
                val prefill = h.session.prefillJavaScript()
                val ready = h.session.readySignalJavaScript()
                h.finishPageLoad()
                // Overlay dismissal is fired first (no callback), prefill second (with callback).
                assertThat(h.webView.scripts).containsExactly(dismiss, prefill).inOrder()
                h.answer("null") // prefill done → PageReady (+ manual-submit hint) and the first poll tick
                assertThat(awaitItem()).isEqualTo(ScrapeEvent.PageReady)
                assertThat(awaitItem()).isEqualTo(ScrapeEvent.NeedsUserAction(UserActionReason.MANUAL_SUBMIT_REQUIRED))
                // Every tick re-runs the dismiss JS (consent SDKs render late) before the ready signal.
                assertThat(h.webView.scripts).containsExactly(dismiss, prefill, dismiss, ready).inOrder()
                h.answer("false")
                ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                assertThat(h.webView.scripts).containsExactly(dismiss, prefill, dismiss, ready, dismiss, ready).inOrder()
                expectNoEvents()
            }
        }

    @Test
    fun `ready signal true dumps the page and the session extracts it`() =
        runTest {
            val h = Harness(context, rule, params)
            val page = requireNotNull(RuleFixtureHarness.fixtureText("indianrail-pnr", "page.html"))
            h.controller.start()
            h.session.events.test {
                h.finishPageLoad()
                h.answer("null")
                skipItems(2) // PageReady + NeedsUserAction
                h.answer("true")
                assertThat(h.lastJs()).isEqualTo(h.session.dumpHtmlJavaScript())
                h.answer(jsonString(page))
                val extracted = awaitItem() as ScrapeEvent.Extracted
                assertThat(extracted.data.fields["trainNumber"]).isEqualTo("12951")
                assertThat(extracted.rawHtml).isEqualTo(page)
            }
        }

    @Test
    fun `ready signal timeout dumps whatever rendered so a stalled page surfaces as ParseFailed`() =
        runTest {
            val h = Harness(context, rule, params, pollIntervalMillis = 100, timeoutMillis = 250)
            h.controller.start()
            h.session.events.test {
                h.finishPageLoad()
                h.answer("null")
                skipItems(2)
                // Three "not ready" ticks push elapsed past the 250 ms budget.
                repeat(3) {
                    assertThat(h.lastJs()).isEqualTo(h.session.readySignalJavaScript())
                    h.answer("false")
                    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
                }
                assertThat(h.lastJs()).isEqualTo(h.session.dumpHtmlJavaScript())
                h.answer(jsonString("<html><body>still loading</body></html>"))
                val failed = awaitItem() as ScrapeEvent.ParseFailed
                assertThat(failed.rawHtml).contains("still loading")
            }
        }

    @Test
    fun `stop ends polling and ignores late results`() =
        runTest {
            val h = Harness(context, rule, params)
            h.controller.start()
            h.session.events.test {
                h.finishPageLoad()
                h.answer("null")
                skipItems(2)
                h.controller.stop()
                h.answer("true") // late ready signal after stop
                assertThat(h.lastJs()).isEqualTo(h.session.readySignalJavaScript()) // no dump was requested
                ShadowLooper.idleMainLooper(1_000, TimeUnit.MILLISECONDS)
                expectNoEvents()
            }
        }

    private fun jsonString(value: String): String = JSONObject.quote(value)
}
