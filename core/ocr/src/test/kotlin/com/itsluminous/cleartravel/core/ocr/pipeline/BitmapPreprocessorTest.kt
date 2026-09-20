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
    fun preservesDimensionsAndProducesGrayscalePixels() {
        val source =
            Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(200, 60, 40))
            }

        val output = BitmapPreprocessor().preprocess(source)

        assertThat(output.width).isEqualTo(16)
        assertThat(output.height).isEqualTo(8)
        val pixel = output.getPixel(8, 4)
        // Grayscale: all channels equal after the saturation(0) + contrast filter.
        assertThat(Color.red(pixel)).isEqualTo(Color.green(pixel))
        assertThat(Color.green(pixel)).isEqualTo(Color.blue(pixel))
    }
}
