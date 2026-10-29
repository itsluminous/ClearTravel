package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 1: renders PDF pages to [Bitmap]s via the platform [PdfRenderer]
 * (no third-party PDF dependency). Ticket/boarding-pass PDFs are rendered at a fixed
 * target width so downstream OCR sees consistent resolution regardless of page size.
 */
@Singleton
class PdfPageRasterizer
    @Inject
    constructor() {
        fun rasterize(
            fileDescriptor: ParcelFileDescriptor,
            pageIndex: Int = 0,
            targetWidthPx: Int = DEFAULT_TARGET_WIDTH_PX,
        ): Bitmap =
            PdfRenderer(fileDescriptor).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount) {
                    "Page $pageIndex out of range (0..${renderer.pageCount - 1})"
                }
                renderer.renderPage(pageIndex, targetWidthPx)
            }

        /**
         * Streams the first [maxPages] pages through [consume] ONE AT A TIME (ADR-045
         * amended): each page is rendered, handed to [consume], and recycled before the
         * next is rendered, so a long itinerary never holds more than one page bitmap
         * (a 2048-px page is ~24 MB in ARGB_8888; airline prints that repeat the
         * itinerary per passenger run to a dozen pages and more). A page that fails to
         * render is skipped, not fatal — the remaining pages are still read. The cap
         * bounds OCR TIME on long terms-and-conditions dumps; pages past it are ignored.
         */
        suspend fun <T> readPages(
            fileDescriptor: ParcelFileDescriptor,
            maxPages: Int = DEFAULT_MAX_PAGES,
            targetWidthPx: Int = DEFAULT_TARGET_WIDTH_PX,
            consume: suspend (Bitmap) -> T,
        ): List<T> =
            PdfRenderer(fileDescriptor).use { renderer ->
                val count = renderer.pageCount.coerceAtMost(maxPages)
                val results = ArrayList<T>(count)
                for (index in 0 until count) {
                    val bitmap = runCatching { renderer.renderPage(index, targetWidthPx) }.getOrNull() ?: continue
                    try {
                        results += consume(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                results
            }

        private fun PdfRenderer.renderPage(
            pageIndex: Int,
            targetWidthPx: Int,
        ): Bitmap =
            openPage(pageIndex).use { page ->
                val scale = targetWidthPx.toFloat() / page.width
                val bitmap =
                    Bitmap.createBitmap(
                        targetWidthPx,
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                // PDFs can have transparent backgrounds; OCR needs white behind text.
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }

        companion object {
            private const val DEFAULT_TARGET_WIDTH_PX = 2048

            /**
             * Pages OCR'd per PDF at most. Pages are streamed, so this bounds TIME, not
             * memory: an airline "Modify Itinerary" print repeats both legs on a page
             * pair PER PASSENGER before its fare and conditions pages (a two-traveller
             * return trip is 9 pages), so the old cap of 8 silently dropped pages.
             */
            const val DEFAULT_MAX_PAGES = 24
        }
    }
