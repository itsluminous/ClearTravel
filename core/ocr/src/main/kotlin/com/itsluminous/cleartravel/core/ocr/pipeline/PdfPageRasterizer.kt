package com.itsluminous.cleartravel.core.ocr.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline stage 1: renders one PDF page to a [Bitmap] via the platform [PdfRenderer]
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
                renderer.openPage(pageIndex).use { page ->
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
            }

        private companion object {
            const val DEFAULT_TARGET_WIDTH_PX = 2048
        }
    }
