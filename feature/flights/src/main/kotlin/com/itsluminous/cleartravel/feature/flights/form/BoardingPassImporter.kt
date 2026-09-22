package com.itsluminous.cleartravel.feature.flights.form

import android.content.Context
import android.net.Uri
import com.itsluminous.cleartravel.core.data.security.AppFileLayout
import com.itsluminous.cleartravel.core.ocr.OcrPrefillService
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.security.file.LocalFileCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature-level seam over `core:ocr` + local file storage so the form ViewModel
 * stays plain-JVM testable (tests substitute a fake; URIs travel as strings).
 */
interface BoardingPassImporter {
    /** Runs the BCBP-barcode-first / OCR-fallback pipeline on the picked file. */
    suspend fun prefill(uriString: String): BoardingPassExtraction

    /**
     * Copies the picked file into app-private storage for offline gate display and
     * returns the stored path — null when the copy failed (form saves without a pass).
     */
    suspend fun store(
        uriString: String,
        flightId: String,
    ): String?
}

/**
 * Production importer: `OcrPrefillService` + an ENCRYPTED copy into
 * `filesDir/boarding_passes/` (ADR-031 — the OCR runs on the picked URI, never on
 * the stored file).
 */
@Singleton
class OcrBoardingPassImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val ocrPrefillService: OcrPrefillService,
        private val fileCipher: LocalFileCipher,
    ) : BoardingPassImporter {
        override suspend fun prefill(uriString: String): BoardingPassExtraction =
            runCatching { ocrPrefillService.prefillBoardingPass(Uri.parse(uriString)) }
                .getOrDefault(BoardingPassExtraction.EMPTY)

        override suspend fun store(
            uriString: String,
            flightId: String,
        ): String? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    val dir = AppFileLayout.boardingPasses(context.filesDir).apply { mkdirs() }
                    val target = File(dir, "$flightId.${extensionOf(uri)}")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        fileCipher.encryptTo(input, target)
                    } ?: return@runCatching null
                    target.absolutePath
                }.getOrNull()
            }

        private fun extensionOf(uri: Uri): String {
            val mime = context.contentResolver.getType(uri)
            return when {
                mime == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true) -> "pdf"
                mime == "image/png" -> "png"
                else -> "jpg"
            }
        }
    }
