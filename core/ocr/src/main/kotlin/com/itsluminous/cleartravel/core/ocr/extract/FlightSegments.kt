package com.itsluminous.cleartravel.core.ocr.extract

import com.itsluminous.cleartravel.core.ocr.model.ExtractedFlight

/** Cross-segment helpers shared by the extractors and the pipeline (ADR-045). */
object FlightSegments {
    /**
     * ADR-025 identity dedupe over flight segments, order preserved: same carrier +
     * number + date → one segment (later mentions fill blanks); same carrier + number
     * with a date on only one side → the undated mention is folded into the dated one
     * (fare/baggage tables repeat the flight without its date); the same flight number
     * on two DIFFERENT dates stays two segments. Segments without a flight identity are
     * kept as they are.
     */
    fun dedupe(segments: List<ExtractedFlight>): List<ExtractedFlight> {
        val result = mutableListOf<ExtractedFlight>()
        for (segment in segments) {
            val flight = segment.flightKey()
            val existing =
                result.indexOfFirst { candidate ->
                    flight != null &&
                        candidate.flightKey() == flight &&
                        (
                            !segment.flightDate.isPresent ||
                                !candidate.flightDate.isPresent ||
                                candidate.flightDate.value == segment.flightDate.value
                        )
                }
            if (existing >= 0) {
                result[existing] = result[existing].fillBlanksFrom(segment)
            } else {
                result += segment
            }
        }
        return result
    }

    /** `IX2019` — the ADR-025 identity minus the date; null without carrier or number. */
    fun ExtractedFlight.flightKey(): String? {
        val carrier =
            carrier.value
                ?.trim()
                ?.uppercase()
                .orEmpty()
        val number =
            flightNumber.value
                ?.trim()
                ?.uppercase()
                ?.trimStart('0')
                .orEmpty()
        return if (carrier.isEmpty() || number.isEmpty()) null else carrier + number
    }
}
