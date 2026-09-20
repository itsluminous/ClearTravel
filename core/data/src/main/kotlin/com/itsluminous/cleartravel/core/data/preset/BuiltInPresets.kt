package com.itsluminous.cleartravel.core.data.preset

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Built-in checklist presets as behavior-as-data (ADR-003/ADR-006): the definitions
 * live in the versioned asset [ASSET_PATH], not in code. Preset ids are FIXED UUIDs
 * so seeding stays idempotent across installs and backup merges never duplicate them.
 */
@Serializable
data class BuiltInPresetsFile(
    /** Data-file schema version (ADR-003). */
    val version: Int,
    val presets: List<BuiltInPresetDefinition>,
)

/** One built-in preset definition from the asset file. */
@Serializable
data class BuiltInPresetDefinition(
    /** Fixed UUID — stable across installs (idempotent seeding + merge safety). */
    val id: String,
    val name: String,
    /** Item texts in display order. */
    val items: List<String>,
)

/** Parses the built-in presets JSON. Pure function — unit-tested against the asset. */
object BuiltInPresetsParser {
    const val ASSET_PATH = "presets/builtin-presets.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(content: String): BuiltInPresetsFile = json.decodeFromString(content)
}

/** Loads the built-in preset definitions. Interface so tests can substitute content. */
interface BuiltInPresetSource {
    fun load(): List<BuiltInPresetDefinition>
}

/** Reads [BuiltInPresetsParser.ASSET_PATH] from the app assets. */
@Singleton
class AssetBuiltInPresetSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BuiltInPresetSource {
        override fun load(): List<BuiltInPresetDefinition> =
            context.assets
                .open(BuiltInPresetsParser.ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
                .let(BuiltInPresetsParser::parse)
                .presets
    }
