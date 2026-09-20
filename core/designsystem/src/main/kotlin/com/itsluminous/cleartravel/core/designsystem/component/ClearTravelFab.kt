package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

private val FabMinTouchTarget = 48.dp

/**
 * The one sanctioned FAB — the primary add action per tab. [content] is typically an
 * `Icon` carrying its own localized `contentDescription` (string resource, never a
 * literal).
 */
@Composable
fun ClearTravelFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape: Shape = FloatingActionButtonDefaults.shape
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = FabMinTouchTarget, minHeight = FabMinTouchTarget),
        shape = shape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        content = content,
    )
}

/**
 * Extended variant of [ClearTravelFab]. [icon] and [text] mirror the Material 3
 * extended-FAB slots.
 */
@Composable
fun ClearTravelExtendedFab(
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = icon,
        text = text,
        modifier = modifier.sizeIn(minWidth = FabMinTouchTarget, minHeight = FabMinTouchTarget),
        shape = FloatingActionButtonDefaults.extendedFabShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}
