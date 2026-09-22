package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentViewerStateTest {
    private val viewport = Size(1000f, 2000f)

    // A 500×1000 bitmap fits the viewport exactly at 2× (1000×2000).
    private val portrait = Size(500f, 1000f)

    // Landscape content: 2000×1000 → fits width at 1000×500.
    private val landscape = Size(2000f, 1000f)

    @Test
    fun `starts at 1x, unrotated, first page, no offset`() {
        val state = DocumentViewerState()
        assertThat(state.scale).isEqualTo(1f)
        assertThat(state.offset).isEqualTo(Offset.Zero)
        assertThat(state.rotationDegrees).isEqualTo(0)
        assertThat(state.pageIndex).isEqualTo(0)
        assertThat(state.pageCount).isEqualTo(1)
        assertThat(state.isZoomed).isFalse()
        assertThat(state.hasMultiplePages).isFalse()
    }

    @Test
    fun `fitted size preserves aspect and fits the viewport`() {
        val state = DocumentViewerState()
        assertThat(state.fittedContentSize(portrait, viewport)).isEqualTo(Size(1000f, 2000f))
        assertThat(state.fittedContentSize(landscape, viewport)).isEqualTo(Size(1000f, 500f))
        assertThat(state.fittedContentSize(Size.Zero, viewport)).isEqualTo(Size.Zero)
        assertThat(state.fittedContentSize(portrait, Size.Zero)).isEqualTo(Size.Zero)
    }

    @Test
    fun `fitted size accounts for sideways rotation`() {
        val state = DocumentViewerState(initialRotationDegrees = 90)
        // Landscape 2000×1000 turned 90° has bounds 1000×2000 → fills the viewport exactly.
        val fitted = state.fittedContentSize(landscape, viewport)
        assertThat(fitted).isEqualTo(Size(2000f, 1000f))
        // Portrait 500×1000 turned 90° has bounds 1000×500 → width-limited to 1000: scale 1.
        assertThat(state.fittedContentSize(portrait, viewport)).isEqualTo(Size(500f, 1000f))
    }

    @Test
    fun `pinch zoom is clamped between 1x and 6x`() {
        val state = DocumentViewerState()
        val fitted = state.fittedContentSize(portrait, viewport)
        val centre = Offset(500f, 1000f)

        state.applyGesture(centre, Offset.Zero, 0.2f, fitted, viewport)
        assertThat(state.scale).isEqualTo(DocumentViewerState.MIN_SCALE)

        state.applyGesture(centre, Offset.Zero, 100f, fitted, viewport)
        assertThat(state.scale).isEqualTo(DocumentViewerState.MAX_SCALE)
        assertThat(state.isZoomed).isTrue()
    }

    @Test
    fun `pan is ignored at 1x and clamped to the content bounds when zoomed`() {
        val state = DocumentViewerState()
        val fitted = state.fittedContentSize(portrait, viewport)
        val centre = Offset(500f, 1000f)

        state.applyGesture(centre, Offset(300f, -400f), 1f, fitted, viewport)
        assertThat(state.offset).isEqualTo(Offset.Zero)

        // 2×: content is 2000×4000 in a 1000×2000 viewport → max |offset| = (500, 1000).
        state.applyGesture(centre, Offset.Zero, 2f, fitted, viewport)
        state.applyGesture(centre, Offset(5000f, -5000f), 1f, fitted, viewport)
        assertThat(state.offset).isEqualTo(Offset(500f, -1000f))
    }

    @Test
    fun `zooming about a centroid keeps the point under the finger fixed`() {
        val state = DocumentViewerState()
        val fitted = state.fittedContentSize(portrait, viewport)
        // Zoom 2× about the top-left quarter point (250, 500): that point maps to
        // content point (250, 500); after zoom it must still be on screen at (250, 500).
        val centroid = Offset(250f, 500f)
        state.applyGesture(centroid, Offset.Zero, 2f, fitted, viewport)
        val centre = Offset(500f, 1000f)
        val screen = centre + (centroid - centre) * state.scale + state.offset
        assertThat(screen.x).isWithin(0.01f).of(250f)
        assertThat(screen.y).isWithin(0.01f).of(500f)
    }

    @Test
    fun `double tap toggles between fitted and the double-tap scale`() {
        val state = DocumentViewerState()
        val fitted = state.fittedContentSize(portrait, viewport)
        val tap = Offset(250f, 500f)

        state.toggleDoubleTapZoom(tap, fitted, viewport)
        assertThat(state.scale).isEqualTo(DocumentViewerState.DOUBLE_TAP_SCALE)
        // The tapped point stays under the finger: centre + (tap-centre)*s + offset == tap.
        val centre = Offset(500f, 1000f)
        val screen = centre + (tap - centre) * state.scale + state.offset
        assertThat(screen.x).isWithin(0.01f).of(250f)
        assertThat(screen.y).isWithin(0.01f).of(500f)

        state.toggleDoubleTapZoom(tap, fitted, viewport)
        assertThat(state.scale).isEqualTo(DocumentViewerState.MIN_SCALE)
        assertThat(state.offset).isEqualTo(Offset.Zero)
    }

    @Test
    fun `double tap near an edge is clamped so no blank space shows`() {
        val state = DocumentViewerState()
        val fitted = state.fittedContentSize(portrait, viewport)
        state.toggleDoubleTapZoom(Offset(0f, 0f), fitted, viewport)
        // 2.5×: content 2500×5000 → max |offset| = (750, 1500). Unclamped would be (750, 1500)·… = 750/1500 exactly.
        assertThat(state.offset.x).isAtMost(750f)
        assertThat(state.offset.y).isAtMost(1500f)
        state.resetZoom()
        state.toggleDoubleTapZoom(Offset(1000f, 2000f), fitted, viewport)
        assertThat(state.offset.x).isAtLeast(-750f)
        assertThat(state.offset.y).isAtLeast(-1500f)
    }

    @Test
    fun `rotation cycles through 90 degree steps and resets zoom`() {
        val state = DocumentViewerState()
        val fitted = state.fittedContentSize(portrait, viewport)
        state.applyGesture(Offset(500f, 1000f), Offset.Zero, 3f, fitted, viewport)
        assertThat(state.isZoomed).isTrue()

        state.rotateClockwise()
        assertThat(state.rotationDegrees).isEqualTo(90)
        assertThat(state.isSideways).isTrue()
        assertThat(state.scale).isEqualTo(1f)
        assertThat(state.offset).isEqualTo(Offset.Zero)

        state.rotateClockwise()
        assertThat(state.rotationDegrees).isEqualTo(180)
        assertThat(state.isSideways).isFalse()
        state.rotateClockwise()
        assertThat(state.rotationDegrees).isEqualTo(270)
        state.rotateClockwise()
        assertThat(state.rotationDegrees).isEqualTo(0)
    }

    @Test
    fun `rotation normalisation handles negative and overshooting degrees`() {
        assertThat(DocumentViewerState.normalizeRotation(-90)).isEqualTo(270)
        assertThat(DocumentViewerState.normalizeRotation(450)).isEqualTo(90)
        assertThat(DocumentViewerState.normalizeRotation(360)).isEqualTo(0)
        assertThat(DocumentViewerState.normalizeRotation(45)).isEqualTo(0)
    }

    @Test
    fun `sideways clamp uses the rotated bounds`() {
        val state = DocumentViewerState(initialRotationDegrees = 90)
        // Landscape 2000×1000 rotated fills 1000×2000 at fit; at 2× bounds are 2000×4000.
        val fitted = state.fittedContentSize(landscape, viewport)
        val centre = Offset(500f, 1000f)
        state.applyGesture(centre, Offset.Zero, 2f, fitted, viewport)
        state.applyGesture(centre, Offset(-9999f, 9999f), 1f, fitted, viewport)
        assertThat(state.offset).isEqualTo(Offset(-500f, 1000f))
    }

    @Test
    fun `page navigation is bounded and resets zoom`() {
        val state = DocumentViewerState(pageCount = 3)
        val fitted = state.fittedContentSize(portrait, viewport)
        assertThat(state.hasMultiplePages).isTrue()
        assertThat(state.canGoPrevious).isFalse()
        assertThat(state.canGoNext).isTrue()

        state.applyGesture(Offset(500f, 1000f), Offset.Zero, 2f, fitted, viewport)
        assertThat(state.nextPage()).isTrue()
        assertThat(state.pageIndex).isEqualTo(1)
        assertThat(state.scale).isEqualTo(1f)

        assertThat(state.nextPage()).isTrue()
        assertThat(state.pageIndex).isEqualTo(2)
        assertThat(state.canGoNext).isFalse()
        assertThat(state.nextPage()).isFalse()
        assertThat(state.pageIndex).isEqualTo(2)

        assertThat(state.goToPage(99)).isFalse() // clamps to 2 == current → no change
        assertThat(state.previousPage()).isTrue()
        assertThat(state.pageIndex).isEqualTo(1)
        assertThat(state.goToPage(-5)).isTrue()
        assertThat(state.pageIndex).isEqualTo(0)
        assertThat(state.previousPage()).isFalse()
    }

    @Test
    fun `updating the page count keeps the index valid`() {
        val state = DocumentViewerState(pageCount = 5, initialPage = 4)
        state.updatePageCount(2)
        assertThat(state.pageCount).isEqualTo(2)
        assertThat(state.pageIndex).isEqualTo(1)
        state.updatePageCount(0)
        assertThat(state.pageCount).isEqualTo(1)
        assertThat(state.pageIndex).isEqualTo(0)
    }

    @Test
    fun `saver keeps page and rotation but not zoom`() {
        val state = DocumentViewerState(pageCount = 4, initialPage = 2, initialRotationDegrees = 180)
        val fitted = state.fittedContentSize(portrait, viewport)
        state.applyGesture(Offset(500f, 1000f), Offset.Zero, 3f, fitted, viewport)

        val saved = with(DocumentViewerState.Saver) { SaverScope { true }.save(state) }
        val restored = checkNotNull(DocumentViewerState.Saver.restore(checkNotNull(saved)))
        assertThat(restored.pageCount).isEqualTo(4)
        assertThat(restored.pageIndex).isEqualTo(2)
        assertThat(restored.rotationDegrees).isEqualTo(180)
        assertThat(restored.scale).isEqualTo(1f)
    }
}
