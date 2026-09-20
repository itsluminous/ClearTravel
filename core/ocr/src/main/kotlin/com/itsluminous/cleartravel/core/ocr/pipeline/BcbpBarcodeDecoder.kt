package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 3 (barcode lane): on-device ML Kit barcode scanning wrapped as a
 * suspend function. The injected scanner is configured for the boarding-pass formats
 * (PDF417, Aztec, QR — see the DI module). Returns raw barcode payloads; scan failure
 * degrades to an empty list so the caller falls back to OCR, never an exception.
 */
@Singleton
class BcbpBarcodeDecoder
    @Inject
    constructor(
        private val scanner: BarcodeScanner,
    ) {
        suspend fun decode(bitmap: Bitmap): List<String> =
            runCatching {
                scanner
                    .process(InputImage.fromBitmap(bitmap, 0))
                    .await()
                    .mapNotNull { it.rawValue }
            }.getOrDefault(emptyList())
    }
