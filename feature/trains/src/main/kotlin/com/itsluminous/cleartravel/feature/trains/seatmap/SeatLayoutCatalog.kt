package com.itsluminous.cleartravel.feature.trains.seatmap

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where seat-layout files come from: app assets at runtime, the filesystem in tests. */
interface SeatLayoutSource {
    /** Class codes with a layout file, e.g. `["1A", "2A", "SL"]`. */
    fun classCodes(): List<String>

    /** The JSON text of one class's layout, or null when there is none. */
    fun open(classCode: String): InputStream?
}

/** `assets/seat-layouts/<classCode>.json`. */
class AssetSeatLayoutSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SeatLayoutSource {
        override fun classCodes(): List<String> =
            context.assets
                .list(DIR)
                .orEmpty()
                .filter { it.endsWith(SUFFIX) }
                .map { it.removeSuffix(SUFFIX) }
                .sorted()

        override fun open(classCode: String): InputStream? = runCatching { context.assets.open("$DIR/$classCode$SUFFIX") }.getOrNull()

        private companion object {
            const val DIR = "seat-layouts"
            const val SUFFIX = ".json"
        }
    }

/** Filesystem-backed source for JVM tests (module dir or repo root as working dir). */
class FileSeatLayoutSource(
    private val dir: File = defaultDir(),
) : SeatLayoutSource {
    override fun classCodes(): List<String> =
        dir
            .listFiles { file -> file.name.endsWith(".json") }
            .orEmpty()
            .map { it.name.removeSuffix(".json") }
            .sorted()

    override fun open(classCode: String): InputStream? = File(dir, "$classCode.json").takeIf(File::isFile)?.inputStream()

    companion object {
        fun defaultDir(): File =
            listOf(
                File("src/main/assets/seat-layouts"),
                File("feature/trains/src/main/assets/seat-layouts"),
            ).firstOrNull(File::isDirectory)
                ?: error("Cannot locate assets/seat-layouts from ${File(".").absolutePath}")
    }
}

/**
 * Lazily parsed, cached seat layouts per class code (ADR-022). A missing or
 * unusable file resolves to null — the UI shows its "no layout for this class"
 * state rather than crashing; the asset test is what keeps every shipped file valid.
 */
@Singleton
class SeatLayoutCatalog
    @Inject
    constructor(
        private val source: SeatLayoutSource,
    ) {
        private val cache = mutableMapOf<String, SeatLayout?>()

        fun layoutFor(classCode: String): SeatLayout? {
            val key = classCode.trim().uppercase()
            if (key.isEmpty()) return null
            synchronized(cache) {
                return cache.getOrPut(key) {
                    source
                        .open(key)
                        ?.use { it.readBytes().decodeToString() }
                        ?.let { text -> runCatching { SeatLayoutEngine.load(text) }.getOrNull() }
                }
            }
        }
    }
