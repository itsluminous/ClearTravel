package com.itsluminous.cleartravel.core.scrape

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScrapeSessionTest {
    private val captchaRule = RuleFixtureHarness.loadRule("indianrail-pnr.json")
    private val params = ScrapeParams(pnr = "8524317690")

    @Test
    fun `captcha rule emits PageReady then NeedsUserAction`() =
        runTest {
            val session = RuleDrivenScrapeSession(captchaRule, params)

            session.events.test {
                session.onPageReady()
                assertThat(awaitItem()).isEqualTo(ScrapeEvent.PageReady)
                assertThat(awaitItem())
                    .isEqualTo(ScrapeEvent.NeedsUserAction(UserActionReason.MANUAL_SUBMIT_REQUIRED))
            }
        }

    @Test
    fun `good html dump emits Extracted with structured data`() =
        runTest {
            val session = RuleDrivenScrapeSession(captchaRule, params)
            val html = requireNotNull(RuleFixtureHarness.fixtureText("indianrail-pnr", "page.html"))

            session.events.test {
                session.onHtmlDumped(html)
                val event = awaitItem() as ScrapeEvent.Extracted
                assertThat(event.data.fields["trainNumber"]).isEqualTo("12951")
                assertThat(event.data.rows).hasSize(2)
            }
        }

    @Test
    fun `broken html dump emits ParseFailed carrying the raw page`() =
        runTest {
            val session = RuleDrivenScrapeSession(captchaRule, params)
            val broken = requireNotNull(RuleFixtureHarness.fixtureText("indianrail-pnr", "broken.html"))

            session.events.test {
                session.onHtmlDumped(broken)
                val event = awaitItem() as ScrapeEvent.ParseFailed
                assertThat(event.rawHtml).isEqualTo(broken)
                assertThat(event.reason).isNotEmpty()
            }
        }

    @Test
    fun `startUrl expands placeholders`() {
        val rule = captchaRule.copy(urlTemplate = "https://example.com/status?pnr={pnr}")
        val session = RuleDrivenScrapeSession(rule, params)

        assertThat(session.startUrl).isEqualTo("https://example.com/status?pnr=8524317690")
    }

    @Test
    fun `prefill js targets the selector with the expanded escaped value`() {
        val session = RuleDrivenScrapeSession(captchaRule, ScrapeParams(pnr = "it's<x>"))

        val js = session.prefillJavaScript()

        assertThat(js).contains("document.querySelector('#inputPnrNo')")
        assertThat(js).contains("el.value='it\\'s\\u003Cx>'")
        assertThat(js).contains("new Event('input'")
    }

    @Test
    fun `submit js is null when the rule has no safe auto-submit`() {
        assertThat(RuleDrivenScrapeSession(captchaRule, params).submitJavaScript()).isNull()
    }

    @Test
    fun `dismiss js is null when the rule declares no dismiss selectors`() {
        assertThat(RuleDrivenScrapeSession(captchaRule, params).dismissJavaScript()).isNull()
    }

    @Test
    fun `dismiss js clicks each declared selector only when visible`() {
        val rule =
            captchaRule.copy(
                dismissSelectors = listOf("#onetrust-accept-btn-handler", ".cookie's-close"),
            )
        val js = requireNotNull(RuleDrivenScrapeSession(rule, params).dismissJavaScript())

        assertThat(js).contains("document.querySelector('#onetrust-accept-btn-handler')")
        assertThat(js).contains("document.querySelector('.cookie\\'s-close')")
        assertThat(js).contains("el.offsetParent!==null")
        assertThat(js).contains(".click()")
    }

    @Test
    fun `airindia rule v2 declares the OneTrust dismissal`() {
        val rule = RuleFixtureHarness.loadRule("airindia.json")

        assertThat(rule.version).isAtLeast(2)
        assertThat(rule.dismissSelectors).containsExactly("#onetrust-accept-btn-handler")
        val js = requireNotNull(RuleDrivenScrapeSession(rule, ScrapeParams()).dismissJavaScript())
        assertThat(js).contains("#onetrust-accept-btn-handler")
    }

    @Test
    fun `submit js clicks the declared selector when present`() {
        val rule = captchaRule.copy(submitSelector = "#SubmitButton")
        val js = RuleDrivenScrapeSession(rule, params).submitJavaScript()

        assertThat(js).contains("document.querySelector('#SubmitButton')")
        assertThat(js).contains(".click()")
    }

    @Test
    fun `ready signal js uses the jsCondition verbatim when declared`() {
        val js = RuleDrivenScrapeSession(captchaRule, params).readySignalJavaScript()

        assertThat(js).contains("pnrOutputDiv")
        assertThat(js).contains("try{")
    }

    @Test
    fun `ready signal js falls back to visible-selector polling`() {
        val rule =
            captchaRule.copy(readySignal = ReadySignal(selector = "#result-table"))
        val js = RuleDrivenScrapeSession(rule, params).readySignalJavaScript()

        assertThat(js).contains("document.querySelector('#result-table')")
        assertThat(js).contains("offsetParent")
    }

    @Test
    fun `erail-route rule is a direct-URL auto-flow with the train number expanded`() {
        val rule = RuleFixtureHarness.loadRule("erail-route.json")
        val session = RuleDrivenScrapeSession(rule, ScrapeParams(trainNumber = "22346"))

        assertThat(session.startUrl).isEqualTo("https://erail.in/train-enquiry/22346")
        // Direct GET: nothing to prefill, nothing to submit, nothing to dismiss —
        // the route flow needs no user interaction (recon 2026-09-21, ADR-018).
        assertThat(rule.prefill).isEmpty()
        assertThat(session.submitJavaScript()).isNull()
        assertThat(session.dismissJavaScript()).isNull()
        assertThat(session.readySignalJavaScript()).contains("DataTable")
    }
}

class ScrapeParamsTest {
    @Test
    fun `expand replaces all known placeholders`() {
        val params = ScrapeParams(pnr = "123", flightNumber = "6E-204", date = "2026-09-25")

        val expanded = params.expand("{pnr}|{flightNumber}|{date}|{unknown}")

        assertThat(expanded).isEqualTo("123|6E-204|2026-09-25|{unknown}")
    }

    @Test
    fun `missing params expand to empty strings`() {
        assertThat(ScrapeParams().expand("p={pnr}&f={flightNumber}")).isEqualTo("p=&f=")
    }

    @Test
    fun `trainNumber placeholder expands and defaults to empty`() {
        assertThat(ScrapeParams(trainNumber = "22346").expand("https://erail.in/train-enquiry/{trainNumber}"))
            .isEqualTo("https://erail.in/train-enquiry/22346")
        assertThat(ScrapeParams().expand("t={trainNumber}")).isEqualTo("t=")
    }

    @Test
    fun `js escaping neutralizes quotes backslashes newlines and tags`() {
        val escaped = "a'b\\c\nd<e".escapeForJsSingleQuotedString()

        assertThat(escaped).isEqualTo("a\\'b\\\\c\\nd\\u003Ce")
    }
}
