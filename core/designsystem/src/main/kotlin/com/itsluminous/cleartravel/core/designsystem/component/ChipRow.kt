package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Semantics test tag carried by every [ChipRow] (UI tests assert scrollability on it). */
const val CHIP_ROW_TEST_TAG = "chip_row"

/**
 * A single-line, horizontally scrollable quick-filter chip row.
 *
 * Filter/assist chip rows must NEVER wrap or shrink their chips to fit the viewport —
 * on narrow screens a plain `Row` squashes the last chip into a one-character-per-line
 * sliver. All quick-filter rows render through this composable instead.
 *
 * [contentPadding] is applied INSIDE the scrollable area, so edge padding scrolls with
 * the content (chips are flush with the screen edge mid-scroll). Callers that sit inside
 * an already-padded container keep the default zero padding; full-bleed callers pass
 * `PaddingValues(horizontal = 16.dp)` and drop the parent padding.
 */
@Composable
fun ChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(CHIP_ROW_TEST_TAG)
                .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
