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
         * Renders the first [maxPages] pages (ADR-045: a confirmation lists its flights
         * across pages and a "boarding pass" PDF may hold one pass per page). The cap
         * bounds OCR time on long itineraries; pages past it are ignored.
         */
        fun rasterizeAll(
            fileDescriptor: ParcelFileDescriptor,
            maxPages: Int = DEFAULT_MAX_PAGES,
            targetWidthPx: Int = DEFAULT_TARGET_WIDTH_PX,
        ): List<Bitmap> =
            PdfRenderer(fileDescriptor).use { renderer ->
                (0 until renderer.pageCount.coerceAtMost(maxPages)).map { renderer.renderPage(it, targetWidthPx) }
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

            /** Pages OCR'd per PDF at most — enough for any itinerary, bounded for long T&C dumps. */
            const val DEFAULT_MAX_PAGES = 8
        }
    }
