package com.itsluminous.cleartravel.feature.trains.prefill

import android.net.Uri
import com.itsluminous.cleartravel.core.ocr.OcrPrefillService
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin seam over [OcrPrefillService] for the two train-ticket prefill paths (pasted
 * SMS/email text, imported PDF/image). Exists so `TrainTicketFormViewModel` depends
 * on an interface plain-JVM tests can fake — the concrete service drags in ML Kit.
 */
interface TrainPrefillSource {
    /** OCR the PDF/image at [uri]; garbage degrades to [TrainTicketExtraction.EMPTY]. */
    suspend fun fromUri(uri: Uri): TrainTicketExtraction

    /** Parse pasted/shared IRCTC SMS or email text — no OCR, pure parsing. */
    fun fromText(text: String): TrainTicketExtraction
}

/** Production [TrainPrefillSource] delegating to the on-device OCR pipeline. */
@Singleton
class OcrTrainPrefillSource
    @Inject
    constructor(
        private val ocrPrefillService: OcrPrefillService,
    ) : TrainPrefillSource {
        override suspend fun fromUri(uri: Uri): TrainTicketExtraction = ocrPrefillService.prefillTrainTicket(uri)

        override fun fromText(text: String): TrainTicketExtraction = ocrPrefillService.prefillTrainTicketFromText(text)
    }
