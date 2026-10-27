package com.itsluminous.cleartravel.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.itsluminous.cleartravel.core.ocr.bcbp.BcbpParseResult
import com.itsluminous.cleartravel.core.ocr.bcbp.BcbpParser
import com.itsluminous.cleartravel.core.ocr.bcbp.toExtractedFlights
import com.itsluminous.cleartravel.core.ocr.extract.BoardingPassTextExtractor
import com.itsluminous.cleartravel.core.ocr.extract.BookingConfirmationExtractor
import com.itsluminous.cleartravel.core.ocr.extract.FlightSegments
import com.itsluminous.cleartravel.core.ocr.extract.IrctcSmsParser
import com.itsluminous.cleartravel.core.ocr.extract.IrctcTicketExtractor
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassExtraction
import com.itsluminous.cleartravel.core.ocr.model.BoardingPassSource
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationExtraction
import com.itsluminous.cleartravel.core.ocr.model.BookingConfirmationSource
import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight
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
 *
 * ADR-045: flight documents are read PAGE BY PAGE (up to
 * [PdfPageRasterizer.DEFAULT_MAX_PAGES]) — each page tries the BCBP barcode lane first
 * and falls back to OCR text; barcode legs and the text of all OCR'd pages (joined, so a
 * shared PNR in a header applies to every page) are merged into ONE list of flight
 * segments deduped by the ADR-025 identity.
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
        private val bookingConfirmationExtractor: BookingConfirmationExtractor,
    ) {
        /** Train-ticket import: OCR the PDF/image at [uri] and extract IRCTC fields. */
        suspend fun prefillTrainTicket(uri: Uri): TrainTicketExtraction =
            withContext(Dispatchers.IO) {
                val bitmap = loadPages(uri, maxPages = 1).firstOrNull() ?: return@withContext TrainTicketExtraction.EMPTY
                val text = textRecognizer.recognize(preprocessor.preprocess(bitmap))
                irctcTicketExtractor.extract(text)
            }

        /** Pasted/shared IRCTC SMS or email text — no OCR, pure parsing. */
        fun prefillTrainTicketFromText(text: String): TrainTicketExtraction = irctcSmsParser.parse(text)

        /**
         * Boarding-pass import: per page, IATA BCBP barcode decode first (authoritative;
         * every leg of an `M2…` barcode is a segment); pages without a decodable barcode
         * fall back to OCR-text heuristics. One pass → one segment; a two-pass page or a
         * pass-per-page PDF → one segment each.
         */
        suspend fun prefillBoardingPass(uri: Uri): BoardingPassExtraction =
            withContext(Dispatchers.IO) {
                val read = readFlightDocument(uri) { boardingPassTextExtractor.extract(it).flights }
                when {
                    read.flights.isEmpty() -> BoardingPassExtraction.EMPTY
                    read.anyBarcode -> BoardingPassExtraction(read.flights, BoardingPassSource.BARCODE)
                    else -> BoardingPassExtraction(read.flights, BoardingPassSource.OCR_TEXT)
                }
            }

        /**
         * Booking-confirmation (e-ticket PDF/image) import: confirmations normally
         * carry no barcode, but when one IS present the BCBP pipeline is reused
         * (authoritative); otherwise OCR-text heuristics extract EVERY flight segment
         * (return trips, multi-city). Never throws — unreadable input degrades to
         * [BookingConfirmationExtraction.EMPTY] (blank form, file attached).
         */
        suspend fun prefillBookingConfirmation(uri: Uri): BookingConfirmationExtraction =
            withContext(Dispatchers.IO) {
                val read = readFlightDocument(uri) { bookingConfirmationExtractor.extract(it).flights }
                when {
                    read.flights.isEmpty() -> BookingConfirmationExtraction.EMPTY
                    read.anyBarcode -> BookingConfirmationExtraction(read.flights, BookingConfirmationSource.BARCODE)
                    else -> BookingConfirmationExtraction(read.flights, BookingConfirmationSource.OCR_TEXT)
                }
            }

        private class DocumentRead(
            val flights: List<ExtractedFlight>,
            val anyBarcode: Boolean,
        )

        private suspend fun readFlightDocument(
            uri: Uri,
            extractText: (String) -> List<ExtractedFlight>,
        ): DocumentRead {
            val pages = loadPages(uri)
            val barcodeFlights = mutableListOf<ExtractedFlight>()
            val texts = mutableListOf<String>()
            for (page in pages) {
                val legs =
                    barcodeDecoder
                        .decode(page)
                        .map { BcbpParser.parse(it) }
                        .filterIsInstance<BcbpParseResult.Success>()
                        .flatMap { it.data.toExtractedFlights() }
                if (legs.isNotEmpty()) {
                    barcodeFlights += legs
                } else {
                    texts += textRecognizer.recognize(preprocessor.preprocess(page))
                }
            }
            val textFlights = if (texts.isEmpty()) emptyList() else extractText(texts.joinToString("\n\n"))
            return DocumentRead(
                flights = FlightSegments.dedupe(barcodeFlights + textFlights),
                anyBarcode = barcodeFlights.isNotEmpty(),
            )
        }

        private fun loadPages(
            uri: Uri,
            maxPages: Int = PdfPageRasterizer.DEFAULT_MAX_PAGES,
        ): List<Bitmap> =
            runCatching {
                if (isPdf(uri)) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        rasterizer.rasterizeAll(it, maxPages = maxPages)
                    } ?: emptyList()
                } else {
                    context.contentResolver.openInputStream(uri)?.use {
                        listOfNotNull(BitmapFactory.decodeStream(it))
                    } ?: emptyList()
                }
            }.getOrDefault(emptyList())

        private fun isPdf(uri: Uri): Boolean =
            context.contentResolver.getType(uri) == "application/pdf" ||
                uri.toString().endsWith(".pdf", ignoreCase = true)
    }
