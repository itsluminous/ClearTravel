package com.itsluminous.cleartravel.core.scrape

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Events emitted by a [RuleDrivenScrapeSession] while a scrape is in flight. */
sealed interface ScrapeEvent {
    /** The target page finished loading and prefill has been injected. */
    data object PageReady : ScrapeEvent

    /**
     * The rule declares no safe auto-submit (e.g. captcha page) — the user must act
     * inside the visible WebView (tap submit, solve any captcha).
     */
    data class NeedsUserAction(
        val reason: UserActionReason,
    ) : ScrapeEvent

    /**
     * The ready signal fired and the dumped HTML parsed successfully. [rawHtml] is
     * the same dump the extraction ran on (ADR-026, additive): a feature-side mapper
     * that needs structure the declarative rule schema cannot express (ARIA
     * tab→panel references, label/value sibling pairing, `<del>` adjacency) can
     * post-process it — the rule still owns the URL/ready/sentinel contract and its
     * fixture still pins what it extracts.
     */
    data class Extracted(
        val data: ScrapedData,
        val rawHtml: String = "",
    ) : ScrapeEvent

    /**
     * The dumped HTML did not match the rule (site changed?). [rawHtml] lets the
     * caller fall back to showing the raw page (spec requirement).
     */
    data class ParseFailed(
        val reason: String,
        val rawHtml: String,
    ) : ScrapeEvent
}

enum class UserActionReason {
    /** Rule has no [ScrapeRule.submitSelector] — usually a captcha-protected form. */
    MANUAL_SUBMIT_REQUIRED,
}

/**
 * One scrape attempt for one rule + params (ADR-008). The session is the
 * WebView-agnostic brain: it produces the URL/JS the host must run and turns the
 * dumped HTML into [events]. WebView lifecycle stays with the CALLER's composable —
 * a thin host ([ScrapeWebViewController]) wires an actual WebView to this session.
 *
 * Feature modules collect [events]: `PageReady` → optionally show the WebView,
 * `NeedsUserAction` → MUST show the WebView, `Extracted` → persist + close,
 * `ParseFailed` → show the raw page fallback.
 */
class RuleDrivenScrapeSession(
    val rule: ScrapeRule,
    val params: ScrapeParams,
) {
    private val eventsFlow =
        MutableSharedFlow<ScrapeEvent>(replay = REPLAY, extraBufferCapacity = BUFFER)

    val events: Flow<ScrapeEvent> = eventsFlow.asSharedFlow()

    /** Fully expanded URL the host must load. */
    val startUrl: String = params.expand(rule.urlTemplate)

    /** JS the host runs after page load: fills every prefill step + fires events. */
    fun prefillJavaScript(): String =
        rule.prefill.joinToString(separator = "\n") { step ->
            val selector = step.selector.escapeForJsSingleQuotedString()
            val value = params.expand(step.valueTemplate).escapeForJsSingleQuotedString()
            "(function(){var el=document.querySelector('$selector');if(el){el.value='$value';" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));}})();"
        }

    /**
     * JS clicking every [ScrapeRule.dismissSelectors] element that exists and is
     * visible (cookie walls and similar blocking overlays), or null when the rule
     * declares none. Idempotent — re-running after the overlay is gone is a no-op —
     * so the host runs it before prefill AND on every poll tick (consent SDKs render
     * asynchronously after page load).
     */
    fun dismissJavaScript(): String? {
        if (rule.dismissSelectors.isEmpty()) return null
        return rule.dismissSelectors.joinToString(separator = "\n") { selector ->
            val escaped = selector.escapeForJsSingleQuotedString()
            "(function(){var el=document.querySelector('$escaped');" +
                "if(el&&el.offsetParent!==null){el.click();}})();"
        }
    }

    /** JS clicking the submit control, or null when auto-submit is unsafe (captcha). */
    fun submitJavaScript(): String? =
        rule.submitSelector?.let { selector ->
            val escaped = selector.escapeForJsSingleQuotedString()
            "(function(){var el=document.querySelector('$escaped');if(el){el.click();}})();"
        }

    /** JS expression the host polls; evaluates to `true` once the result rendered. */
    fun readySignalJavaScript(): String {
        val condition = rule.readySignal.jsCondition
        if (condition != null) return "(function(){try{return !!($condition);}catch(e){return false;}})()"
        val selector =
            rule.readySignal.selector
                .orEmpty()
                .escapeForJsSingleQuotedString()
        return "(function(){var el=document.querySelector('$selector');" +
            "return !!el && el.offsetParent !== null;})()"
    }

    /** JS expression whose (string) result is the full document dump. */
    fun dumpHtmlJavaScript(): String = "document.documentElement.outerHTML"

    /** Host callback: page finished loading and prefill was injected. */
    fun onPageReady() {
        eventsFlow.tryEmit(ScrapeEvent.PageReady)
        if (rule.submitSelector == null) {
            eventsFlow.tryEmit(
                ScrapeEvent.NeedsUserAction(UserActionReason.MANUAL_SUBMIT_REQUIRED),
            )
        }
    }

    /** Host callback: ready signal fired and the DOM was dumped. Runs extraction. */
    fun onHtmlDumped(html: String) {
        when (val result = RuleExtractor.extract(rule, html)) {
            is ExtractionResult.Success -> eventsFlow.tryEmit(ScrapeEvent.Extracted(result.data, rawHtml = html))
            is ExtractionResult.Failure ->
                eventsFlow.tryEmit(
                    ScrapeEvent.ParseFailed(reason = result.reason, rawHtml = result.rawHtml),
                )
        }
    }

    private companion object {
        const val REPLAY = 8
        const val BUFFER = 8
    }
}
