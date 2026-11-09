package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 2: simple on-device preprocessing before recognition — grayscale +
 * contrast stretch via [ColorMatrix] filters only (no OpenCV dependency; deskew is
 * deliberately skipped, see ADR-009). Printed tickets/boarding passes are already
 * axis-aligned scans/photos in practice, and grayscale+contrast is where most of the
 * OCR accuracy win comes from.
 *
 * ADR-050: a bitmap narrower than the PDF working width ([MIN_WORKING_WIDTH_PX], what
 * [PdfPageRasterizer] renders pages at) is first upscaled by an INTEGER factor (at most
 * [MAX_UPSCALE]) until it reaches it. A phone screenshot of a trip view prints its body
 * text ~20 px tall, below what the Latin recognizer reads reliably — it dropped a digit
 * of `11:05 PM` and read `8IUT7P` as `8IUTZP` at native size, and read both right at
 * twice the size. An integer factor keeps glyph edges crisp; a page already at working
 * width (a rasterized PDF, a 2300-px scan) is left as it is.
 */
@Singleton
class BitmapPreprocessor
    @Inject
    constructor() {
        fun preprocess(source: Bitmap): Bitmap {
            val factor = upscaleFactor(source.width)
            val width = source.width * factor
            val height = source.height * factor
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val matrix =
                ColorMatrix().apply {
                    setSaturation(0f) // grayscale
                    postConcat(contrastMatrix(CONTRAST_SCALE))
                }
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
            Canvas(output).drawBitmap(source, null, Rect(0, 0, width, height), paint)
            return output
        }

        /** The integer factor that lifts [width] to at least [MIN_WORKING_WIDTH_PX], capped at [MAX_UPSCALE]; 1 when already there. */
        fun upscaleFactor(width: Int): Int {
            if (width <= 0 || width >= MIN_WORKING_WIDTH_PX) return 1
            return ((MIN_WORKING_WIDTH_PX + width - 1) / width).coerceIn(1, MAX_UPSCALE)
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

        companion object {
            private const val CONTRAST_SCALE = 1.4f

            /** The width [PdfPageRasterizer] renders pages at; images are lifted to it before OCR. */
            const val MIN_WORKING_WIDTH_PX = 2048

            /** Tiny inputs (thumbnails) are not blown up without limit. */
            const val MAX_UPSCALE = 3
        }
    }
