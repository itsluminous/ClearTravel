package com.itsluminous.cleartravel.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.itsluminous.cleartravel.core.ocr.bcbp.BcbpParseResult
import com.itsluminous.cleartravel.core.ocr.bcbp.BcbpParser
import com.itsluminous.cleartravel.core.ocr.bcbp.toBoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.extract.BoardingPassTextExtractor
import com.itsluminous.cleartravel.core.ocr.extract.IrctcSmsParser
import com.itsluminous.cleartravel.core.ocr.extract.IrctcTicketExtractor
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import com.itsluminous.cleartravel.core.ocr.pipeline.BcbpBarcodeDecoder
import com.itsluminous.cleartravel.core.ocr.pipeline.BitmapPreprocessor
import com.itsluminous.cleartravel.core.ocr.pipeline.OcrTextRecognizer
import com.itsluminous.cleartravel.core.ocr.pipeline.PdfPageRasterizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public entry point of the on-device OCR/barcode pipeline (the ONLY class feature
 * modules need for file imports). Takes a content [Uri] (PDF or image), runs
 * rasterize -> preprocess -> recognize/decode -> extract, entirely on-device — files
 * never leave the device. Results always prefill the editable add form (per-field
 * confidence, never a blind save); anything unrecognized degrades to the EMPTY
 * result (blank form + file attached), never an exception.
 */
@Singleton
class OcrPrefillService
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val rasterizer: PdfPageRasterizer,
        private val preprocessor: BitmapPreprocessor,
        private val textRecognizer: OcrTextRecognizer,
        private val barcodeDecoder: BcbpBarcodeDecoder,
        private val irctcTicketExtractor: IrctcTicketExtractor,
        private val irctcSmsParser: IrctcSmsParser,
        private val boardingPassTextExtractor: BoardingPassTextExtractor,
    ) {
        /** Train-ticket import: OCR the PDF/image at [uri] and extract IRCTC fields. */
        suspend fun prefillTrainTicket(uri: Uri): TrainTicketExtraction =
            withContext(Dispatchers.IO) {
                val bitmap = loadBitmap(uri) ?: return@withContext TrainTicketExtraction.EMPTY
                val text = textRecognizer.recognize(preprocessor.preprocess(bitmap))
                irctcTicketExtractor.extract(text)
            }

        /** Pasted/shared IRCTC SMS or email text — no OCR, pure parsing. */
        fun prefillTrainTicketFromText(text: String): TrainTicketExtraction = irctcSmsParser.parse(text)

        /**
         * Boarding-pass import: IATA BCBP barcode decode first (authoritative); if no
         * barcode is found or none parses, falls back to OCR-text heuristics.
         */
        suspend fun prefillBoardingPass(uri: Uri): BoardingPassExtraction =
            withContext(Dispatchers.IO) {
                val bitmap = loadBitmap(uri) ?: return@withContext BoardingPassExtraction.EMPTY
                for (payload in barcodeDecoder.decode(bitmap)) {
                    val result = BcbpParser.parse(payload)
                    if (result is BcbpParseResult.Success) {
                        return@withContext result.data.toBoardingPassExtraction()
                    }
                }
                val text = textRecognizer.recognize(preprocessor.preprocess(bitmap))
                boardingPassTextExtractor.extract(text)
            }

        private fun loadBitmap(uri: Uri): Bitmap? =
            runCatching {
                if (isPdf(uri)) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        rasterizer.rasterize(it)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
            }.getOrNull()

        private fun isPdf(uri: Uri): Boolean =
            context.contentResolver.getType(uri) == "application/pdf" ||
                uri.toString().endsWith(".pdf", ignoreCase = true)
    }
