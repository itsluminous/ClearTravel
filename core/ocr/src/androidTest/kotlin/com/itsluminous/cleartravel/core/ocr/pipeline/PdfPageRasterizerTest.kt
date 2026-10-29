package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [PdfPageRasterizer.readPages] on a real [android.graphics.pdf.PdfRenderer] (no
 * Robolectric shadow exists for it): a 10-page PDF built on the device with
 * [PdfDocument] is streamed page by page — every page past the OLD cap of 8 is read,
 * `maxPages` still bounds the walk, each bitmap reaches the consumer un-recycled at the
 * rendered width and is recycled once the consumer returns (ADR-045 amended: one page in
 * memory at a time).
 */
@RunWith(AndroidJUnit4::class)
class PdfPageRasterizerTest {
    private lateinit var pdf: File

    @Before
    fun writeTenPagePdf() {
        val context = InstrumentationRegistry.getInstrumentation().context
        pdf = File(context.cacheDir, "ten-pages.pdf")
        val document = PdfDocument()
        val paint =
            Paint().apply {
                color = Color.BLACK
                textSize = 24f
            }
        repeat(PAGES) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
            page.canvas.drawText("Page ${index + 1}", 40f, 80f, paint)
            document.finishPage(page)
        }
        pdf.outputStream().use { document.writeTo(it) }
        document.close()
    }

    @After
    fun deletePdf() {
        pdf.delete()
    }

    private fun <T> read(
        maxPages: Int = PdfPageRasterizer.DEFAULT_MAX_PAGES,
        consume: (Bitmap) -> T,
    ): List<T> =
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            runBlocking { PdfPageRasterizer().readPages(fd, maxPages = maxPages, targetWidthPx = WIDTH) { consume(it) } }
        }

    @Test
    fun streamsEveryPageOneAtATimeAndRecyclesEachAfterTheConsumer() {
        val seen = mutableListOf<Bitmap>()

        val heights =
            read { bitmap ->
                assertThat(bitmap.isRecycled).isFalse()
                assertThat(bitmap.width).isEqualTo(WIDTH)
                // Only the CURRENT page is alive: every earlier one was recycled already.
                assertThat(seen.all { it.isRecycled }).isTrue()
                seen += bitmap
                bitmap.height
            }

        assertThat(heights).hasSize(PAGES)
        assertThat(heights.toSet()).containsExactly((842 * WIDTH / 595f).toInt())
        assertThat(seen.all { it.isRecycled }).isTrue()
    }

    @Test
    fun theDefaultCapNoLongerDropsAnAirlinePrintsNinthPage() {
        assertThat(PdfPageRasterizer.DEFAULT_MAX_PAGES).isAtLeast(PAGES)
        assertThat(read { 1 }.sum()).isEqualTo(PAGES)
    }

    @Test
    fun maxPagesBoundsTheWalk() {
        assertThat(read(maxPages = 3) { 1 }).hasSize(3)
    }

    @Test
    fun singlePageRasterizeStillRendersTheRequestedPage() {
        val bitmap =
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfPageRasterizer().rasterize(fd, pageIndex = PAGES - 1, targetWidthPx = WIDTH)
            }
        assertThat(bitmap.width).isEqualTo(WIDTH)
        assertThat(bitmap.isRecycled).isFalse()
        bitmap.recycle()
    }

    private companion object {
        const val PAGES = 10
        const val WIDTH = 256
    }
}
