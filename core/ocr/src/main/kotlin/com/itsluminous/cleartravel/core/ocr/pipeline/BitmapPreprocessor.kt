package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 2: simple on-device preprocessing before recognition — grayscale +
 * contrast stretch via [ColorMatrix] filters only (no OpenCV dependency; deskew is
 * deliberately skipped, see ADR-009). Printed tickets/boarding passes are already
 * axis-aligned scans/photos in practice, and grayscale+contrast is where most of the
 * OCR accuracy win comes from.
 */
@Singleton
class BitmapPreprocessor
    @Inject
    constructor() {
        fun preprocess(source: Bitmap): Bitmap {
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val matrix =
                ColorMatrix().apply {
                    setSaturation(0f) // grayscale
                    postConcat(contrastMatrix(CONTRAST_SCALE))
                }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
            Canvas(output).drawBitmap(source, 0f, 0f, paint)
            return output
        }

        /** Linear contrast stretch around the mid-gray point: `c' = scale*(c-128)+128`. */
        private fun contrastMatrix(scale: Float): ColorMatrix {
            val translate = (1f - scale) * 128f
            return ColorMatrix(
                floatArrayOf(
                    scale,
                    0f,
                    0f,
                    0f,
                    translate,
                    0f,
                    scale,
                    0f,
                    0f,
                    translate,
                    0f,
                    0f,
                    scale,
                    0f,
                    translate,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )
        }

        private companion object {
            const val CONTRAST_SCALE = 1.4f
        }
    }
