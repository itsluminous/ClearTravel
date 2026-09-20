package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 3: on-device ML Kit text recognition wrapped as a suspend function.
 * Returns the recognized text as plain lines for the pure extractors; recognition
 * failure degrades to an empty string (blank-form fallback), never an exception.
 */
@Singleton
class OcrTextRecognizer
    @Inject
    constructor(
        private val client: TextRecognizer,
    ) {
        suspend fun recognize(bitmap: Bitmap): String =
            runCatching {
                client.process(InputImage.fromBitmap(bitmap, 0)).await().text
            }.getOrDefault("")
    }
