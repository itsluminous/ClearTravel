package com.itsluminous.cleartravel.feature.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.designsystem.component.ReorderHandle
import com.itsluminous.cleartravel.core.designsystem.component.ReorderableListState
import com.itsluminous.cleartravel.core.designsystem.component.TextEditDialog
import com.itsluminous.cleartravel.core.designsystem.component.rememberReorderableListState
import com.itsluminous.cleartravel.core.designsystem.component.reorderableItem
import com.itsluminous.cleartravel.core.model.ChecklistPresetItem

/**
 * Preset editor — rename + add/edit/remove/drag-reorder items. Built-in presets are
 * editable exactly like user presets (ADR-021); the tombstone-aware seeder never
 * overwrites an edited built-in. Editing a preset never mutates existing checklists —
 * appends are copies (ADR-006).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetEditScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PresetEditViewModel = hiltViewModel(),
) {
    val preset by viewModel.preset.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()

    var name by rememberSaveable { mutableStateOf("") }
    var nameInitialised by rememberSaveable { mutableStateOf(false) }
    var editingItemId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(preset?.id) {
        if (!nameInitialised && preset != null) {
            name = preset?.name.orEmpty()
            nameInitialised = true
        }
    }
    val reorderState =
        rememberReorderableListState(
            items = items,
            key = { it.id },
            onDrop = viewModel::moveItem,
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_preset_edit_title)) },
                navigationIcon = {
                    ExplainableIcon(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        explanationRes = R.string.menu_back,
                        onClick = {
                            viewModel.rename(name)
                            onBack()
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.menu_preset_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = stringResource(R.string.menu_preset_no_items_title),
                    message = stringResource(R.string.menu_preset_no_items_message),
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = reorderState.lazyListState,
                    contentPadding = PaddingValues(top = 8.dp),
                ) {
                    items(reorderState.items, key = { it.id }) { item ->
                        PresetItemRow(
                            item = item,
                            reorderState = reorderState,
                            onEdit = { editingItemId = item.id },
                            onDelete = { viewModel.removeItem(item.id) },
                            modifier = Modifier.reorderableItem(reorderState, item.id, this),
                        )
                    }
                }
            }
            AddPresetItemField(onAdd = viewModel::addItem)
        }
    }

    val editingItem = items.firstOrNull { it.id == editingItemId }
    if (editingItem != null) {
        TextEditDialog(
            title = stringResource(R.string.menu_preset_edit_item_title),
            label = stringResource(R.string.menu_preset_edit_item_label),
            initialValue = editingItem.text,
            confirmText = stringResource(R.string.menu_preset_save),
            dismissText = stringResource(R.string.menu_cancel),
            onConfirm = { text ->
                viewModel.renameItem(editingItem.id, text)
                editingItemId = null
            },
            onDismiss = { editingItemId = null },
        )
    }
}

@Composable
private fun PresetItemRow(
    item: ChecklistPresetItem,
    reorderState: ReorderableListState<ChecklistPresetItem>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReorderHandle(state = reorderState, itemKey = item.id)
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        ExplainableIcon(
            icon = Icons.Filled.Edit,
            explanationRes = R.string.menu_preset_item_edit,
            targetSize = 40.dp,
            iconSize = 20.dp,
            onClick = onEdit,
        )
        ExplainableIcon(
            icon = Icons.Filled.Delete,
            explanationRes = R.string.menu_preset_item_delete,
            targetSize = 40.dp,
            iconSize = 20.dp,
            onClick = onDelete,
        )
    }
}

@Composable
private fun AddPresetItemField(
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.menu_preset_add_item_hint)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        ExplainableIcon(
            icon = Icons.Filled.Add,
            explanationRes = R.string.menu_preset_add_item_action,
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text)
                    text = ""
                }
            },
        )
    }
}
