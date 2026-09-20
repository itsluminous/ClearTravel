package com.itsluminous.cleartravel.core.ocr

/**
 * Confidence attached to every OCR field extraction (skeleton stub): extraction output
 * always prefills the editable add form together with this indication — the user
 * reviews and saves; nothing OCR-derived is ever saved blind.
 */
enum class ExtractionConfidence {
    HIGH,
    MEDIUM,
    LOW,

    /** Nothing recognized — the form opens blank with the source file attached. */
    NONE,
}
