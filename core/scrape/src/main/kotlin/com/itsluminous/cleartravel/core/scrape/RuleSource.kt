package com.itsluminous.cleartravel.core.scrape

import android.content.Context
import java.io.InputStream

/**
 * Where rule JSON files come from. The runtime uses [AssetRuleSource]; unit tests use
 * a filesystem-backed source so [RuleRegistry] stays pure and JVM-testable.
 */
interface RuleSource {
    /** File names (e.g. `indianrail-pnr.json`) of every available rule file. */
    fun ruleFileNames(): List<String>

    fun openRule(fileName: String): InputStream
}

/** Production source: reads `assets/scrape-rules/` bundled in this module. */
class AssetRuleSource(
    context: Context,
) : RuleSource {
    private val assets = context.applicationContext.assets

    override fun ruleFileNames(): List<String> =
        assets
            .list(RULES_ASSET_DIR)
            .orEmpty()
            .filter { it.endsWith(".json") }
            .sorted()

    override fun openRule(fileName: String): InputStream = assets.open("$RULES_ASSET_DIR/$fileName")

    companion object {
        const val RULES_ASSET_DIR = "scrape-rules"
    }
}
