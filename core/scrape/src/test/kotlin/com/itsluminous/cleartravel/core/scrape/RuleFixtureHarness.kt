package com.itsluminous.cleartravel.core.scrape

import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * Shared plumbing for the fixture test harness (ADR-003/ADR-008): locates the
 * `assets/scrape-rules/` directory on the filesystem (unit tests run with the module
 * directory — or the repo root — as the working dir) and fixture files on the test
 * classpath (`src/test/resources/fixtures/<ruleId>/...`).
 *
 * CONTRACT for adding a rule (e.g. an airline): drop `<ruleId>.json` into
 * `core/scrape/src/main/assets/scrape-rules/` AND a recorded fixture pair into
 * `core/scrape/src/test/resources/fixtures/<ruleId>/{page.html,expected.json}` —
 * [RuleFixtureTest] enumerates every rule file and FAILS for any missing fixture.
 */
object RuleFixtureHarness {
    val json = Json { ignoreUnknownKeys = true }

    fun assetRulesDir(): File {
        val candidates =
            listOf(
                File("src/main/assets/scrape-rules"),
                File("core/scrape/src/main/assets/scrape-rules"),
            )
        return candidates.firstOrNull(File::isDirectory)
            ?: error(
                "Cannot locate assets/scrape-rules from working dir ${File(".").absolutePath}; " +
                    "checked: ${candidates.joinToString { it.path }}",
            )
    }

    fun ruleFileNames(): List<String> =
        assetRulesDir()
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .map(File::getName)
            .sorted()

    fun loadRule(fileName: String): ScrapeRule =
        json.decodeFromString(
            ScrapeRule.serializer(),
            File(assetRulesDir(), fileName).readText(),
        )

    /** Null when the fixture file does not exist on the test classpath. */
    fun fixtureText(
        ruleId: String,
        fileName: String,
    ): String? =
        javaClass.classLoader
            ?.getResourceAsStream("fixtures/$ruleId/$fileName")
            ?.use(InputStream::readBytes)
            ?.toString(Charsets.UTF_8)

    /**
     * The enforcement rule itself, reusable so it can be self-tested: returns one
     * human-actionable error per missing fixture file for [ruleId].
     */
    fun missingFixtureErrors(ruleId: String): List<String> =
        listOf("page.html", "expected.json").mapNotNull { fileName ->
            if (fixtureText(ruleId, fileName) == null) {
                "Rule '$ruleId' has no fixture '$fileName' — record one at " +
                    "core/scrape/src/test/resources/fixtures/$ruleId/$fileName " +
                    "(a rule file without a fixture fails CI by design, ADR-003)"
            } else {
                null
            }
        }

    /** Filesystem-backed [RuleSource] so registry tests read the real asset files. */
    class FileRuleSource(
        private val dir: File = assetRulesDir(),
    ) : RuleSource {
        override fun ruleFileNames(): List<String> =
            dir
                .listFiles { file -> file.name.endsWith(".json") }
                .orEmpty()
                .map(File::getName)
                .sorted()

        override fun openRule(fileName: String): InputStream = File(dir, fileName).inputStream()
    }
}
