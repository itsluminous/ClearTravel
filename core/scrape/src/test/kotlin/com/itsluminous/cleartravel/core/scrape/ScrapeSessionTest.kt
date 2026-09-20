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
    fun `js escaping neutralizes quotes backslashes newlines and tags`() {
        val escaped = "a'b\\c\nd<e".escapeForJsSingleQuotedString()

        assertThat(escaped).isEqualTo("a\\'b\\\\c\\nd\\u003Ce")
    }
}
