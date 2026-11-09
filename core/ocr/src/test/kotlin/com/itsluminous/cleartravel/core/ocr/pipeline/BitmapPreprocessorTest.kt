package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Thin Robolectric coverage — the ONLY test in this module that needs an Android
 * runtime (Bitmap/Canvas). Everything else runs as plain JUnit per ADR-009.
 * Native graphics mode makes Canvas/ColorMatrix render for real instead of no-op
 * legacy shadows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapPreprocessorTest {
    @Test
    fun preservesDimensionsAtWorkingWidthAndProducesGrayscalePixels() {
        val source =
            Bitmap.createBitmap(BitmapPreprocessor.MIN_WORKING_WIDTH_PX, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(200, 60, 40))
            }

        val output = BitmapPreprocessor().preprocess(source)

        assertThat(output.width).isEqualTo(BitmapPreprocessor.MIN_WORKING_WIDTH_PX)
        assertThat(output.height).isEqualTo(8)
        val pixel = output.getPixel(8, 4)
        // Grayscale: all channels equal after the saturation(0) + contrast filter.
        assertThat(Color.red(pixel)).isEqualTo(Color.green(pixel))
        assertThat(Color.green(pixel)).isEqualTo(Color.blue(pixel))
    }

    /** ADR-050: a phone screenshot (1098 px wide) is doubled; a tiny input is capped; a page at working width is untouched. */
    @Test
    fun upscalesNarrowImagesByAnIntegerFactorToTheWorkingWidth() {
        val preprocessor = BitmapPreprocessor()
        assertThat(preprocessor.upscaleFactor(1098)).isEqualTo(2)
        assertThat(preprocessor.upscaleFactor(1278)).isEqualTo(2)
        assertThat(preprocessor.upscaleFactor(700)).isEqualTo(3)
        assertThat(preprocessor.upscaleFactor(16)).isEqualTo(BitmapPreprocessor.MAX_UPSCALE)
        assertThat(preprocessor.upscaleFactor(2048)).isEqualTo(1)
        assertThat(preprocessor.upscaleFactor(2300)).isEqualTo(1)
        assertThat(preprocessor.upscaleFactor(0)).isEqualTo(1)

        val source = Bitmap.createBitmap(1098, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = preprocessor.preprocess(source)
        assertThat(output.width).isEqualTo(2196)
        assertThat(output.height).isEqualTo(40)
    }
}
