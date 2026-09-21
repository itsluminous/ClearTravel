package com.itsluminous.cleartravel.core.scrape

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleExtractorTest {
    private fun rule(
        extract: Map<String, ExtractSpec> = emptyMap(),
        rows: RowExtract? = null,
        postProcess: Map<String, PostProcessHint> = emptyMap(),
        extraRows: Map<String, RowExtract> = emptyMap(),
    ) = ScrapeRule(
        id = "test-rule",
        displayName = "Test",
        version = 1,
        kind = RuleKind.TRAIN,
        urlTemplate = "https://example.com/{pnr}",
        readySignal = ReadySignal(selector = "#done"),
        extract = extract,
        rows = rows,
        postProcess = postProcess,
        extraRows = extraRows,
    )

    private val stationRows =
        RowExtract(
            rowSelector = "table tr",
            fields = mapOf("station" to ExtractSpec(selector = "td")),
            minRows = 1,
        )

    private val coachRows =
        RowExtract(
            rowSelector = ".coaches .coach",
            fields = mapOf("code" to ExtractSpec()),
        )

    @Test
    fun `extraRows extracts a named secondary row-set next to the primary rows`() {
        val html =
            """
            <table><tr><td>A</td></tr><tr><td>B</td></tr></table>
            <div class="coaches"><div class="coach">EN </div><div class="coach">S1</div><div class="coach">B4</div></div>
            """.trimIndent()

        val result = RuleExtractor.extract(rule(rows = stationRows, extraRows = mapOf("coaches" to coachRows)), html)

        val data = (result as ExtractionResult.Success).data
        assertThat(data.rows.map { it["station"] }).containsExactly("A", "B").inOrder()
        assertThat(data.extraRows.keys).containsExactly("coaches")
        assertThat(data.extraRows.getValue("coaches").map { it["code"] }).containsExactly("EN", "S1", "B4").inOrder()
    }

    @Test
    fun `extraRows section absent from the page yields an empty list and the primary rows still succeed`() {
        val html = "<table><tr><td>A</td></tr><tr><td>B</td></tr></table>"

        val result = RuleExtractor.extract(rule(rows = stationRows, extraRows = mapOf("coaches" to coachRows)), html)

        val data = (result as ExtractionResult.Success).data
        assertThat(data.rows).hasSize(2)
        assertThat(data.extraRows).containsExactly("coaches", emptyList<Map<String, String>>())
    }

    @Test
    fun `extraRows minRows is enforced per set with the set name in the reason`() {
        val html = "<table><tr><td>A</td></tr></table>"
        val strict = coachRows.copy(minRows = 1)

        val result = RuleExtractor.extract(rule(rows = stationRows, extraRows = mapOf("coaches" to strict)), html)

        val failure = result as ExtractionResult.Failure
        assertThat(failure.reason).contains("Row-set 'coaches'")
        assertThat(failure.reason).contains("at least 1")
    }

    @Test
    fun `rules without extraRows produce an empty extraRows map`() {
        val html = "<table><tr><td>A</td></tr></table>"

        val result = RuleExtractor.extract(rule(rows = stationRows), html)

        assertThat((result as ExtractionResult.Success).data.extraRows).isEmpty()
    }

    @Test
    fun `extraRows alone count as extracted content`() {
        val html = "<div class=\"coaches\"><div class=\"coach\">EN</div></div>"

        val result = RuleExtractor.extract(rule(extraRows = mapOf("coaches" to coachRows)), html)

        assertThat(result).isInstanceOf(ExtractionResult.Success::class.java)
    }

    @Test
    fun `regex chain applies patterns sequentially taking group 1`() {
        val html = "<div id='status'>Flight 6E-204 departs 18:45 from T1</div>"
        val spec =
            ExtractSpec(
                selector = "#status",
                regexChain = listOf("departs (.+)", "(\\d{2}:\\d{2})"),
            )

        val result = RuleExtractor.extract(rule(extract = mapOf("dep" to spec)), html)

        val data = (result as ExtractionResult.Success).data
        assertThat(data.fields["dep"]).isEqualTo("18:45")
    }

    @Test
    fun `regex without capture group uses the whole match`() {
        val html = "<span id='s'>PNR 8524317690 OK</span>"
        val spec = ExtractSpec(selector = "#s", regexChain = listOf("\\d{10}"))

        val result = RuleExtractor.extract(rule(extract = mapOf("pnr" to spec)), html)

        assertThat((result as ExtractionResult.Success).data.fields["pnr"]).isEqualTo("8524317690")
    }

    @Test
    fun `non-matching regex yields empty value`() {
        val html = "<span id='s'>no digits here</span><span id='ok'>keep</span>"
        val specs =
            mapOf(
                "missing" to ExtractSpec(selector = "#s", regexChain = listOf("\\d+")),
                "present" to ExtractSpec(selector = "#ok"),
            )

        val result = RuleExtractor.extract(rule(extract = specs), html)

        val data = (result as ExtractionResult.Success).data
        assertThat(data.fields["missing"]).isEmpty()
        assertThat(data.fields["present"]).isEqualTo("keep")
    }

    @Test
    fun `invalid regex pattern degrades to empty value instead of throwing`() {
        val html = "<span id='s'>value</span><span id='ok'>keep</span>"
        val specs =
            mapOf(
                "bad" to ExtractSpec(selector = "#s", regexChain = listOf("([unclosed")),
                "present" to ExtractSpec(selector = "#ok"),
            )

        val result = RuleExtractor.extract(rule(extract = specs), html)

        assertThat((result as ExtractionResult.Success).data.fields["bad"]).isEmpty()
    }

    @Test
    fun `attribute extraction reads the attribute value`() {
        val html = "<a id='link' href='https://x.example/path'>text</a>"
        val spec = ExtractSpec(selector = "#link", attribute = "href")

        val result = RuleExtractor.extract(rule(extract = mapOf("url" to spec)), html)

        assertThat((result as ExtractionResult.Success).data.fields["url"])
            .isEqualTo("https://x.example/path")
    }

    @Test
    fun `date postProcess normalizes single-digit day-month to iso`() {
        val html = "<span id='d'>5-1-2026</span>"
        val result =
            RuleExtractor.extract(
                rule(
                    extract = mapOf("date" to ExtractSpec(selector = "#d")),
                    postProcess =
                        mapOf(
                            "date" to
                                PostProcessHint(
                                    type = "date",
                                    inputFormats = listOf("d-M-uuuu"),
                                    outputFormat = "uuuu-MM-dd",
                                ),
                        ),
                ),
                html,
            )

        assertThat((result as ExtractionResult.Success).data.fields["date"]).isEqualTo("2026-01-05")
    }

    @Test
    fun `time postProcess reformats with default iso output`() {
        val html = "<span id='t'>6:05 PM</span>"
        val result =
            RuleExtractor.extract(
                rule(
                    extract = mapOf("time" to ExtractSpec(selector = "#t")),
                    postProcess =
                        mapOf("time" to PostProcessHint(type = "time", inputFormats = listOf("h:mm a"))),
                ),
                html,
            )

        assertThat((result as ExtractionResult.Success).data.fields["time"]).isEqualTo("18:05")
    }

    @Test
    fun `unparseable postProcess value keeps its raw form`() {
        val html = "<span id='d'>Not A Date</span>"
        val result =
            RuleExtractor.extract(
                rule(
                    extract = mapOf("date" to ExtractSpec(selector = "#d")),
                    postProcess =
                        mapOf("date" to PostProcessHint(type = "date", inputFormats = listOf("d-M-uuuu"))),
                ),
                html,
            )

        assertThat((result as ExtractionResult.Success).data.fields["date"]).isEqualTo("Not A Date")
    }

    @Test
    fun `trim postProcess collapses whitespace`() {
        val html = "<span id='n'>MUMBAI    RAJDHANI</span>"
        val result =
            RuleExtractor.extract(
                rule(
                    extract = mapOf("name" to ExtractSpec(selector = "#n")),
                    postProcess = mapOf("name" to PostProcessHint(type = "trim")),
                ),
                html,
            )

        assertThat((result as ExtractionResult.Success).data.fields["name"]).isEqualTo("MUMBAI RAJDHANI")
    }

    @Test
    fun `blank required field fails with the field name in the reason`() {
        val html = "<div id='other'>present</div>"
        val specs =
            mapOf(
                "must" to ExtractSpec(selector = "#gone", required = true),
                "other" to ExtractSpec(selector = "#other"),
            )

        val result = RuleExtractor.extract(rule(extract = specs), html)

        val failure = result as ExtractionResult.Failure
        assertThat(failure.reason).contains("must")
        assertThat(failure.rawHtml).isEqualTo(html)
    }

    @Test
    fun `fewer rows than minRows fails`() {
        val html = "<table id='t'><tr><td>only-header</td></tr></table>"
        val rows =
            RowExtract(
                rowSelector = "#missing-table tr",
                fields = mapOf("cell" to ExtractSpec(selector = "td")),
                minRows = 1,
            )

        val result =
            RuleExtractor.extract(
                rule(extract = mapOf("any" to ExtractSpec(selector = "#t td")), rows = rows),
                html,
            )

        assertThat(result).isInstanceOf(ExtractionResult.Failure::class.java)
        assertThat((result as ExtractionResult.Failure).reason).contains("row")
    }

    @Test
    fun `row fields resolve relative to each row element`() {
        val html =
            """
            <table id="t">
              <tr class="r"><td>A1</td><td>A2</td></tr>
              <tr class="r"><td>B1</td><td>B2</td></tr>
            </table>
            """.trimIndent()
        val rows =
            RowExtract(
                rowSelector = "#t tr.r",
                fields =
                    mapOf(
                        "first" to ExtractSpec(selector = "td:nth-of-type(1)"),
                        "second" to ExtractSpec(selector = "td:nth-of-type(2)"),
                    ),
                minRows = 2,
            )

        val result = RuleExtractor.extract(rule(rows = rows), html)

        val data = (result as ExtractionResult.Success).data
        assertThat(data.rows)
            .containsExactly(
                mapOf("first" to "A1", "second" to "A2"),
                mapOf("first" to "B1", "second" to "B2"),
            ).inOrder()
    }

    @Test
    fun `completely empty extraction fails rather than returning blank success`() {
        val html = "<html><body><p>unrelated</p></body></html>"
        val result =
            RuleExtractor.extract(
                rule(extract = mapOf("a" to ExtractSpec(selector = "#nope"))),
                html,
            )

        assertThat(result).isInstanceOf(ExtractionResult.Failure::class.java)
    }
}
