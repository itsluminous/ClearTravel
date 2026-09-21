package com.itsluminous.cleartravel.core.designsystem.component

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.itsluminous.cleartravel.core.designsystem.R

/**
 * Drag-to-reorder state for a `LazyColumn` whose ONLY children are the reorderable
 * items (no headers — layout indices must equal list indices).
 *
 * The state owns a locally reordered copy of the caller's list ([items]) so the row
 * under the finger swaps with its neighbours live while dragging (neighbours slide via
 * `Modifier.animateItem()`, the dragged row follows the pointer through a
 * `graphicsLayer` translation). On drop, [ReorderableListState.onDrop] receives the
 * ORIGINAL start index and the FINAL index exactly once, so callers persist a single
 * `move(from, to)`; the local order is kept until the caller's [items] change
 * (typically the Room Flow re-emitting), which avoids a flicker back to the old order.
 *
 * Self-contained: no auto-scroll while dragging (packing lists are short), and the drag
 * is initiated by a dedicated handle ([ReorderHandle]) rather than a long-press on the
 * whole row so plain taps on the row keep their meaning.
 */
@Stable
class ReorderableListState<T> internal constructor(
    val lazyListState: LazyListState,
    initialItems: List<T>,
    private val key: (T) -> Any,
    private val onDrop: (from: Int, to: Int) -> Unit,
) {
    /** The list to render — the caller's items, reordered live while a drag is active. */
    var items: List<T> by mutableStateOf(initialItems)
        private set

    /** Key of the row currently being dragged, or null. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    private var dragOffset by mutableFloatStateOf(0f)
    private var fromIndex = -1
    private var currentIndex = -1

    val isDragging: Boolean get() = draggingKey != null

    internal fun sync(latest: List<T>) {
        if (!isDragging) items = latest
    }

    /** Vertical translation (px) to apply to the row with [itemKey]; 0 for idle rows. */
    fun offsetFor(itemKey: Any): Float = if (itemKey == draggingKey) dragOffset else 0f

    internal fun onDragStart(itemKey: Any) {
        val index = items.indexOfFirst { key(it) == itemKey }
        if (index < 0) return
        draggingKey = itemKey
        dragOffset = 0f
        fromIndex = index
        currentIndex = index
    }

    internal fun onDrag(deltaY: Float) {
        val draggingKey = draggingKey ?: return
        dragOffset += deltaY
        val dragged = visibleInfo(draggingKey) ?: return
        val centre = dragged.offset + dragOffset + dragged.size / 2f
        val target =
            lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                info.key != draggingKey && centre >= info.offset && centre < info.offset + info.size
            } ?: return
        val from = dragged.index
        val to = target.index
        if (from == to || to !in items.indices) return
        items = items.moved(from, to)
        currentIndex = to
        // Keep the dragged row under the finger: compensate for the layout offset it
        // is about to take after the swap.
        val newOffset = if (to > from) target.offset + target.size - dragged.size else target.offset
        dragOffset -= (newOffset - dragged.offset)
    }

    internal fun onDragEnd() {
        val from = fromIndex
        val to = currentIndex
        draggingKey = null
        dragOffset = 0f
        fromIndex = -1
        currentIndex = -1
        if (from >= 0 && to >= 0 && from != to) onDrop(from, to)
    }

    private fun visibleInfo(itemKey: Any): LazyListItemInfo? = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey }
}

/**
 * Remembers a [ReorderableListState] mirroring [items] (re-synced whenever [items]
 * change while no drag is active). Render `state.items`, not [items].
 */
@Composable
fun <T> rememberReorderableListState(
    items: List<T>,
    key: (T) -> Any,
    onDrop: (from: Int, to: Int) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(),
): ReorderableListState<T> {
    val state = remember(lazyListState) { ReorderableListState(lazyListState, items, key, onDrop) }
    LaunchedEffect(items) { state.sync(items) }
    return state
}

/**
 * Row modifier for a reorderable item: translates the dragged row with the pointer,
 * lifts it above its neighbours, and animates every other row's placement.
 */
fun <T> Modifier.reorderableItem(
    state: ReorderableListState<T>,
    itemKey: Any,
    scope: LazyItemScope,
): Modifier {
    val dragging = state.draggingKey == itemKey
    val base = if (dragging) this else with(scope) { this@reorderableItem.animateItem() }
    return base
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer { translationY = state.offsetFor(itemKey) }
}

/**
 * The drag handle (Material "drag indicator" dotted grid) placed at the START of a
 * reorderable row. Pressing and dragging the handle reorders immediately — no
 * long-press needed — and consumes the gesture so the list does not scroll instead.
 * Long-press explains the handle like every other icon-only control.
 */
@Composable
fun <T> ReorderHandle(
    state: ReorderableListState<T>,
    itemKey: Any,
    modifier: Modifier = Modifier,
    targetSize: Dp = 40.dp,
) {
    ExplainableIcon(
        icon = Icons.Filled.DragIndicator,
        explanationRes = R.string.designsystem_reorder_handle,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        targetSize = targetSize,
        iconSize = 24.dp,
        modifier =
            modifier.pointerInput(state, itemKey) {
                detectDragGestures(
                    onDragStart = { state.onDragStart(itemKey) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        state.onDrag(dragAmount.y)
                    },
                    onDragEnd = { state.onDragEnd() },
                    onDragCancel = { state.onDragEnd() },
                )
            },
    )
}
