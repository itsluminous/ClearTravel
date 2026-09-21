package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * Single-line [Text] that SHRINKS its font to fit the available width instead of
 * wrapping — for narrow slots like the labels of equal-weight form fields, where a
 * longer label (e.g. "Booking status") would otherwise wrap to two lines. The size
 * steps down on measured overflow until it fits or reaches [minScale]; anything
 * still overflowing at the floor ellipsizes.
 */
@Composable
fun AutoShrinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    minScale: Float = MIN_SCALE_DEFAULT,
) {
    // Label slots inherit their size from LocalTextStyle; guard the rare unspecified case.
    val baseSize = if (style.fontSize.isUnspecified) FALLBACK_FONT_SIZE_SP.sp else style.fontSize
    var scale by remember(text) { mutableFloatStateOf(1f) }
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = style.copy(fontSize = baseSize * scale),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && scale > minScale) {
                scale = (scale - SCALE_STEP).coerceAtLeast(minScale)
            }
        },
        modifier = modifier,
    )
}

private const val MIN_SCALE_DEFAULT = 0.6f
private const val SCALE_STEP = 0.05f
private const val FALLBACK_FONT_SIZE_SP = 16
