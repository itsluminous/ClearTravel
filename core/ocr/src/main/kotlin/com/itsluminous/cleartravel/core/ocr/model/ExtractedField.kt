package com.itsluminous.cleartravel.core.ocr.model

import com.itsluminous.cleartravel.core.ocr.ExtractionConfidence
import kotlinx.serialization.Serializable

/**
 * A single extracted field value paired with the confidence of its extraction.
 * `value == null` always pairs with [ExtractionConfidence.NONE] — an unrecognized
 * field simply leaves the corresponding form field blank.
 */
@Serializable
data class ExtractedField(
    val value: String? = null,
    val confidence: ExtractionConfidence = ExtractionConfidence.NONE,
) {
    val isPresent: Boolean get() = !value.isNullOrBlank()

    companion object {
        val EMPTY = ExtractedField()

        /** Non-blank value with the given confidence; blank/null collapses to [EMPTY]. */
        fun of(
            value: String?,
            confidence: ExtractionConfidence,
        ): ExtractedField = if (value.isNullOrBlank()) EMPTY else ExtractedField(value.trim(), confidence)
    }
}
