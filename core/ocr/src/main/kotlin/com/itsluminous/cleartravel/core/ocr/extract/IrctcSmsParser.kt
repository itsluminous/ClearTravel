package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import com.itsluminous.cleartravel.core.ocr.model.ExtractedField
import com.itsluminous.cleartravel.core.ocr.model.PassengerExtraction
import com.itsluminous.cleartravel.core.ocr.model.TrainTicketExtraction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure parser for pasted IRCTC booking SMS/email text, e.g.
 * `PNR:8524167890,TRN:12951,DOJ:20-09-25,3A,NDLS-BCT,DP:16:25,RAHUL SHARMA+1,B4 32,CNF,...`.
 * Same output type as the OCR extractor so both prefill the same add form.
 * Always succeeds structurally: garbage input returns [TrainTicketExtraction.EMPTY].
 */
@Singleton
class IrctcSmsParser
    @Inject
    constructor() {
        fun parse(text: String): TrainTicketExtraction = runCatching { parseInternal(text) }.getOrDefault(TrainTicketExtraction.EMPTY)

        private fun parseInternal(text: String): TrainTicketExtraction {
            val pnr =
                PNR
                    .find(text)
                    ?.let { ExtractedField.of(it.groupValues[1], ExtractionConfidence.HIGH) }
                    ?: ExtractedField.EMPTY
            // Structure signal: without at least a labeled PNR or TRN this is not an
            // IRCTC SMS — bail out to the blank form instead of guessing.
            val train = TRAIN.find(text)
            if (!pnr.isPresent && train == null) return TrainTicketExtraction.EMPTY

            val doj = DOJ.find(text)?.let { OcrDates.parseToIso(it.groupValues[1]) }
            val route = ROUTE.find(text)
            return TrainTicketExtraction(
                pnr = pnr,
                trainNumber =
                    ExtractedField.of(train?.groupValues?.get(1), ExtractionConfidence.HIGH),
                journeyDate = ExtractedField.of(doj, ExtractionConfidence.HIGH),
                fromStation =
                    ExtractedField.of(route?.groupValues?.get(1), ExtractionConfidence.MEDIUM),
                toStation =
                    ExtractedField.of(route?.groupValues?.get(2), ExtractionConfidence.MEDIUM),
                travelClass =
                    ExtractedField.of(
                        CLASS.find(text)?.groupValues?.get(1),
                        ExtractionConfidence.MEDIUM,
                    ),
                passengers = parsePassengers(text),
            )
        }

        private fun parsePassengers(text: String): List<PassengerExtraction> {
            val name =
                NAME
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            val berths = BERTH.findAll(text).toList()
            if (name == null && berths.isEmpty()) return emptyList()
            val count = maxOf(1, berths.size)
            return (0 until count).map { i ->
                val berth = berths.getOrNull(i)
                PassengerExtraction(
                    // The SMS names only the lead passenger ("RAHUL SHARMA+1").
                    name =
                        if (i == 0) {
                            ExtractedField.of(name, ExtractionConfidence.MEDIUM)
                        } else {
                            ExtractedField.EMPTY
                        },
                    coach =
                        ExtractedField.of(
                            berth?.groupValues?.get(1),
                            ExtractionConfidence.MEDIUM,
                        ),
                    berth =
                        ExtractedField.of(
                            berth?.groupValues?.get(2),
                            ExtractionConfidence.MEDIUM,
                        ),
                    bookingStatus =
                        ExtractedField.of(
                            STATUS.find(text)?.groupValues?.get(1),
                            ExtractionConfidence.MEDIUM,
                        ),
                )
            }
        }

        private companion object {
            val PNR = Regex("""PNR\s*[:\-]?\s*(\d{10})""", RegexOption.IGNORE_CASE)
            val TRAIN = Regex("""TR(?:AI)?N\s*[:\-]?\s*(\d{5})""", RegexOption.IGNORE_CASE)
            val DOJ = Regex("""DOJ\s*[:\-]?\s*([0-9]{1,2}[-/.][0-9]{1,2}[-/.][0-9]{2,4})""", RegexOption.IGNORE_CASE)
            val ROUTE = Regex("""\b([A-Z]{2,5})\s*-\s*([A-Z]{2,5})\b""")
            val CLASS = Regex("""\b(1A|2A|3A|3E|EA|EC|CC|SL|2S|FC)\b""")

            /** Lead passenger name, optionally with a "+n" companion count. */
            val NAME = Regex("""\b([A-Z][A-Za-z]+(?:\s+[A-Z][A-Za-z]+)+)\s*(?:\+\d)?\s*,""")

            /** Coach + berth pairs: `B4 32`, `S4 33`. */
            val BERTH = Regex("""\b([A-Z]{1,2}\d{1,2})\s+(\d{1,3})\b""")
            val STATUS = Regex("""\b(CNF|RAC\s*\d*|WL\s*\d*|CAN)\b""")
        }
    }
