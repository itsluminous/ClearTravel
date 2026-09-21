package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 3: on-device ML Kit text recognition wrapped as a suspend function.
 * Returns the recognized text in READING ORDER (rows rebuilt from line geometry by
 * [OcrLayout], not ML Kit's block order) for the pure extractors; recognition failure
 * degrades to an empty string (blank-form fallback), never an exception.
 */
@Singleton
class OcrTextRecognizer
    @Inject
    constructor(
        private val client: TextRecognizer,
    ) {
        suspend fun recognize(bitmap: Bitmap): String =
            runCatching {
                client.process(InputImage.fromBitmap(bitmap, 0)).await().toRowText()
            }.getOrDefault("")

        private fun Text.toRowText(): String {
            val lines =
                textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    OcrLine(box.left, box.top, box.right, box.bottom, line.text)
                }
            // No geometry at all (never seen in practice): keep ML Kit's own order.
            return if (lines.isEmpty()) text else OcrLayout.toRowText(lines)
        }
    }
