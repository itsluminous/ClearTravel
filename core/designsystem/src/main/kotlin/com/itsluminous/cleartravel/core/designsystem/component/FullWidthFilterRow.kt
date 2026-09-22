package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A row of equal-width filter segments spanning the screen (ADR-033 §3): the same
 * 32dp Material 3 [FilterChip]s, each stretched with `weight(1f)` so the row reads as
 * ONE full-width control without growing taller. Used for the Active|Archived filter
 * on the trains, flights and trips lists — [ChipRow] stays the choice for genuinely
 * multi-chip, scrollable rows (type presets in dialogs).
 *
 * Put only [FullWidthFilterChip]s inside; each takes its share of the width itself.
 */
@Composable
fun FullWidthFilterRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One equal-width segment of a [FullWidthFilterRow]: a [FilterChip] with a centered label. */
@Composable
fun RowScope.FullWidthFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        modifier = modifier.weight(1f),
    )
}
