package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/**
 * View-level state of the shared document viewer (ADR-030): zoom scale, pan offset,
 * 90° rotation and the current PDF page. Pure Kotlin on top of Compose snapshot state
 * so it is unit-testable without a device, and so [DocumentViewerScreen] stays a thin
 * gesture/render shell.
 *
 * Geometry model (matches `Modifier.graphicsLayer` with the default centre transform
 * origin): a content point `c` lands on screen at
 * `centre + scale · R(rotation) · (c − centre) + offset`, where `offset` is in screen
 * pixels. Rotation never changes the file — it only turns the rendered bitmap.
 */
class DocumentViewerState(
    pageCount: Int = 1,
    initialPage: Int = 0,
    initialRotationDegrees: Int = 0,
) {
    /** Zoom factor, always within [MIN_SCALE]..[MAX_SCALE]. */
    var scale: Float by mutableFloatStateOf(MIN_SCALE)
        private set

    /** Pan offset in screen pixels, clamped so the content never leaves the viewport. */
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** One of 0, 90, 180, 270. */
    var rotationDegrees: Int by mutableIntStateOf(normalizeRotation(initialRotationDegrees))
        private set

    /** Zero-based index of the visible page. Always `0` for images. */
    var pageIndex: Int by mutableIntStateOf(initialPage.coerceIn(0, max(0, pageCount - 1)))
        private set

    /** Total pages; `1` for images, updated once a PDF has been opened. */
    var pageCount: Int by mutableIntStateOf(max(1, pageCount))
        private set

    val isZoomed: Boolean get() = scale > MIN_SCALE + EPSILON

    val hasMultiplePages: Boolean get() = pageCount > 1

    val canGoPrevious: Boolean get() = pageIndex > 0

    val canGoNext: Boolean get() = pageIndex < pageCount - 1

    /** Whether the rotation swaps the content's width and height on screen. */
    val isSideways: Boolean get() = rotationDegrees == 90 || rotationDegrees == 270

    /**
     * Layout size for the un-rotated content so that its ROTATED bounding box fits
     * inside [viewport] (aspect preserved, never upscaled beyond fit). Returns
     * [Size.Zero] for degenerate inputs.
     */
    fun fittedContentSize(
        contentSize: Size,
        viewport: Size,
    ): Size {
        if (contentSize.width <= 0f || contentSize.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) {
            return Size.Zero
        }
        val boundsWidth = if (isSideways) contentSize.height else contentSize.width
        val boundsHeight = if (isSideways) contentSize.width else contentSize.height
        val fit = min(viewport.width / boundsWidth, viewport.height / boundsHeight)
        return Size(contentSize.width * fit, contentSize.height * fit)
    }

    /**
     * Applies one transform-gesture step: [zoomChange] about [centroid] plus [pan],
     * keeping the point under the centroid fixed, then clamps scale and offset.
     * [fittedSize] is the value [fittedContentSize] returned for the current layout.
     */
    fun applyGesture(
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
        fittedSize: Size,
        viewport: Size,
    ) {
        val previousScale = scale
        val newScale = (previousScale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val ratio = newScale / previousScale
        val newOffset = centroid + pan - centre - (centroid - centre - offset) * ratio
        scale = newScale
        offset = clampOffset(newOffset, newScale, fittedSize, viewport)
    }

    /**
     * Double-tap: zoom to [DOUBLE_TAP_SCALE] keeping [tap] under the finger when at
     * rest, otherwise return to the fitted 1× view.
     */
    fun toggleDoubleTapZoom(
        tap: Offset,
        fittedSize: Size,
        viewport: Size,
    ) {
        if (isZoomed) {
            resetZoom()
            return
        }
        val centre = Offset(viewport.width / 2f, viewport.height / 2f)
        val target = DOUBLE_TAP_SCALE
        scale = target
        offset = clampOffset((tap - centre) * (1f - target), target, fittedSize, viewport)
    }

    /** Re-clamps the offset after the viewport or content size changed (e.g. rotation). */
    fun reclamp(
        fittedSize: Size,
        viewport: Size,
    ) {
        offset = clampOffset(offset, scale, fittedSize, viewport)
    }

    fun resetZoom() {
        scale = MIN_SCALE
        offset = Offset.Zero
    }

    /** Rotates the VIEW by +90°, cycling 0 → 90 → 180 → 270 → 0, and resets zoom. */
    fun rotateClockwise() {
        rotationDegrees = normalizeRotation(rotationDegrees + ROTATION_STEP)
        resetZoom()
    }

    /** Records the page count once the document is opened; keeps [pageIndex] valid. */
    fun updatePageCount(count: Int) {
        pageCount = max(1, count)
        if (pageIndex > pageCount - 1) pageIndex = pageCount - 1
    }

    fun goToPage(index: Int): Boolean {
        val target = index.coerceIn(0, pageCount - 1)
        if (target == pageIndex) return false
        pageIndex = target
        resetZoom()
        return true
    }

    fun nextPage(): Boolean = canGoNext && goToPage(pageIndex + 1)

    fun previousPage(): Boolean = canGoPrevious && goToPage(pageIndex - 1)

    private fun clampOffset(
        candidate: Offset,
        atScale: Float,
        fittedSize: Size,
        viewport: Size,
    ): Offset {
        val boundsWidth = (if (isSideways) fittedSize.height else fittedSize.width) * atScale
        val boundsHeight = (if (isSideways) fittedSize.width else fittedSize.height) * atScale
        val maxX = max(0f, (boundsWidth - viewport.width) / 2f)
        val maxY = max(0f, (boundsHeight - viewport.height) / 2f)
        return Offset(clampAxis(candidate.x, maxX), clampAxis(candidate.y, maxY))
    }

    /** `coerceIn(-0f, 0f)` can yield −0.0, which [Offset] treats as ≠ 0.0; normalise it. */
    private fun clampAxis(
        value: Float,
        limit: Float,
    ): Float = if (limit <= 0f) 0f else value.coerceIn(-limit, limit)

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f
        const val ROTATION_STEP = 90
        private const val FULL_TURN = 360
        private const val EPSILON = 0.001f

        internal fun normalizeRotation(degrees: Int): Int = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN / ROTATION_STEP * ROTATION_STEP

        /** Survives rotation/process death: page and view rotation are kept, zoom is not. */
        val Saver =
            listSaver<DocumentViewerState, Int>(
                save = { listOf(it.pageCount, it.pageIndex, it.rotationDegrees) },
                restore = { DocumentViewerState(pageCount = it[0], initialPage = it[1], initialRotationDegrees = it[2]) },
            )
    }
}

/** Remembers a [DocumentViewerState] keyed on [path]; page + rotation survive config changes. */
@Composable
fun rememberDocumentViewerState(path: String): DocumentViewerState =
    rememberSaveable(path, saver = DocumentViewerState.Saver) { DocumentViewerState() }
