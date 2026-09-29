package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * Single-line [Text] that SHRINKS its font to fit the available width instead of
 * wrapping — the shell-wide rule for every single-line UI label (button and chip
 * labels, segmented / filter / tab rows, card title rows, stat pills, dialog action
 * buttons): a label that no longer fits its slot (a narrower device, font scale 1.3,
 * a longer dynamic value) gets smaller, never taller.
 *
 * On measured overflow the size jumps straight to the ratio of available to natural
 * width (one relayout, not a staircase), verifies, and steps further down if the
 * glyph metrics were not perfectly linear; it never goes below [minScale], and
 * anything still overflowing at the floor ellipsizes. The line height of [style] is
 * kept, so the slot keeps its height while the glyphs get smaller.
 *
 * [color], [fontWeight], [fontStyle] and [textAlign] pass through to [Text] exactly
 * like the plain component, so an existing label swaps in without restyling.
 */
@Composable
fun AutoShrinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    textAlign: TextAlign? = null,
    minScale: Float = MIN_SCALE_DEFAULT,
) {
    // Label slots inherit their size from LocalTextStyle; guard the rare unspecified case.
    val baseSize = if (style.fontSize.isUnspecified) FALLBACK_FONT_SIZE_SP.sp else style.fontSize
    val measured =
        style.copy(
            fontSize = baseSize,
            fontWeight = fontWeight ?: style.fontWeight,
            fontStyle = fontStyle ?: style.fontStyle,
        )
    val measurer = rememberTextMeasurer()
    var scale by remember(text, measured) { mutableFloatStateOf(1f) }
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        color = color,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textAlign = textAlign,
        style = style.copy(fontSize = baseSize * scale),
        onTextLayout = { result ->
            if (!result.hasVisualOverflow || scale <= minScale) return@Text
            val available = result.layoutInput.constraints.maxWidth
            val natural =
                measurer
                    .measure(
                        text = text,
                        style = measured.copy(fontSize = baseSize * scale),
                        maxLines = 1,
                        softWrap = false,
                    ).size.width
            scale = nextShrinkScale(scale, available, natural, minScale)
        },
        modifier = modifier,
    )
}

/**
 * The next scale to try after an overflow at [current]: the proportional fit of
 * [availablePx] over [naturalPx] (with a small safety margin), always at least one
 * [SCALE_STEP] below [current] so the loop makes progress, floored at [minScale].
 * Unusable measurements (an unbounded slot, an empty label) fall back to one step.
 */
internal fun nextShrinkScale(
    current: Float,
    availablePx: Int,
    naturalPx: Int,
    minScale: Float,
): Float {
    val step = current - SCALE_STEP
    val fit =
        if (availablePx in 1 until Constraints.Infinity && naturalPx > 0) {
            current * (availablePx.toFloat() / naturalPx) * FIT_MARGIN
        } else {
            step
        }
    return minOf(fit, step).coerceAtLeast(minScale)
}

private const val MIN_SCALE_DEFAULT = 0.6f
private const val SCALE_STEP = 0.05f
private const val FIT_MARGIN = 0.97f
private const val FALLBACK_FONT_SIZE_SP = 16
