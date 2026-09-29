package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.ui.unit.Constraints
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoShrinkTextTest {
    @Test
    fun `jumps straight to the proportional fit with a safety margin`() {
        // 300px of text in a 150px slot: half size, minus the 3% margin (floor kept out of the way).
        val next = nextShrinkScale(current = 1f, availablePx = 150, naturalPx = 300, minScale = 0.4f)
        assertThat(next).isWithin(0.001f).of(0.485f)
    }

    @Test
    fun `always makes progress by at least one step`() {
        // A 1% overflow: the proportional fit (0.99 * 0.97 = 0.96) is above one step (0.95),
        // so the step wins and the loop cannot stall on rounding.
        val next = nextShrinkScale(current = 1f, availablePx = 297, naturalPx = 300, minScale = 0.6f)
        assertThat(next).isWithin(0.001f).of(0.95f)
    }

    @Test
    fun `never goes below the floor`() {
        val next = nextShrinkScale(current = 0.62f, availablePx = 50, naturalPx = 400, minScale = 0.6f)
        assertThat(next).isEqualTo(0.6f)
    }

    @Test
    fun `falls back to one step when the slot is unbounded or the label is empty`() {
        assertThat(nextShrinkScale(1f, Constraints.Infinity, 300, 0.6f)).isWithin(0.001f).of(0.95f)
        assertThat(nextShrinkScale(1f, 0, 300, 0.6f)).isWithin(0.001f).of(0.95f)
        assertThat(nextShrinkScale(1f, 150, 0, 0.6f)).isWithin(0.001f).of(0.95f)
    }

    @Test
    fun `scales relative to the current size, not the base`() {
        // Already at 0.8 and still 20% too wide: 0.8 * 0.8 * 0.97.
        val next = nextShrinkScale(current = 0.8f, availablePx = 240, naturalPx = 300, minScale = 0.6f)
        assertThat(next).isWithin(0.001f).of(0.6208f)
    }
}
